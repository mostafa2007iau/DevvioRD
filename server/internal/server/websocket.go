package server

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// A small, dependency-free RFC 6455 WebSocket implementation.
//
// Only what DevvioRD needs is implemented: server-side upgrade, binary/text
// data messages (with fragmentation), control frames (ping/pong/close) and
// strict length handling. No extensions are negotiated (the video payload is
// already compressed, so permessage-deflate would only burn CPU).

const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

const (
	opContinuation = 0x0
	opText         = 0x1
	opBinary       = 0x2
	opClose        = 0x8
	opPing         = 0x9
	opPong         = 0xA
)

// maxMessageSize guards memory: 32 MiB is far above any frame or JSON control
// message we send, and keeps a hostile peer from allocating unbounded memory.
const maxMessageSize = 32 << 20

var (
	errWSClosed  = errors.New("ws: closed")
	errWSControl = errors.New("ws: protocol error")
)

type wsConn struct {
	c  net.Conn
	br *bufio.Reader

	wmu sync.Mutex

	mu      sync.Mutex
	closed  bool
	closeMu sync.Once

	fragOp  byte
	fragBuf []byte

	pingPayload []byte
}

// upgradeWS performs the HTTP -> WebSocket handshake.
func upgradeWS(w http.ResponseWriter, r *http.Request) (*wsConn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		http.Error(w, "expected websocket upgrade", http.StatusBadRequest)
		return nil, errWSControl
	}
	if !headerContainsToken(r.Header.Get("Connection"), "upgrade") {
		http.Error(w, "bad connection header", http.StatusBadRequest)
		return nil, errWSControl
	}
	if r.Header.Get("Sec-WebSocket-Version") != "13" {
		w.Header().Set("Sec-WebSocket-Version", "13")
		http.Error(w, "unsupported websocket version", http.StatusUpgradeRequired)
		return nil, errWSControl
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return nil, errWSControl
	}
	h := sha1.New()
	h.Write([]byte(key))
	h.Write([]byte(wsGUID))
	accept := base64.StdEncoding.EncodeToString(h.Sum(nil))

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return nil, errWSControl
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		return nil, err
	}
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := rw.WriteString(resp); err != nil {
		conn.Close()
		return nil, err
	}
	if err := rw.Flush(); err != nil {
		conn.Close()
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return &wsConn{c: conn, br: bufio.NewReaderSize(rw.Reader, 1<<16)}, nil
}

func headerContainsToken(h, token string) bool {
	for _, part := range strings.Split(h, ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}

// readMessage returns the next complete data message.
// Control frames are handled internally (ping -> pong, close -> error).
func (c *wsConn) readMessage(timeout time.Duration) (byte, []byte, error) {
	if timeout > 0 {
		_ = c.c.SetReadDeadline(time.Now().Add(timeout))
	}
	for {
		op, fin, payload, err := c.readFrame()
		if err != nil {
			return 0, nil, err
		}
		switch op {
		case opPing:
			_ = c.writeFrame(opPong, payload)
			continue
		case opPong:
			continue
		case opClose:
			_ = c.writeClose(1000, "bye")
			return 0, nil, errWSClosed
		case opText, opBinary:
			if fin {
				return op, payload, nil
			}
			c.fragOp = op
			c.fragBuf = append(c.fragBuf[:0], payload...)
			continue
		case opContinuation:
			if c.fragOp == 0 {
				return 0, nil, errWSControl
			}
			c.fragBuf = append(c.fragBuf, payload...)
			if len(c.fragBuf) > maxMessageSize {
				return 0, nil, fmt.Errorf("ws: message too large")
			}
			if fin {
				op := c.fragOp
				buf := c.fragBuf
				c.fragOp = 0
				c.fragBuf = nil
				return op, buf, nil
			}
			continue
		default:
			return 0, nil, errWSControl
		}
	}
}

func (c *wsConn) readFrame() (op byte, fin bool, payload []byte, err error) {
	var hdr [2]byte
	if _, err = io.ReadFull(c.br, hdr[:]); err != nil {
		return
	}
	fin = hdr[0]&0x80 != 0
	op = hdr[0] & 0x0F
	masked := hdr[1]&0x80 != 0
	length := int64(hdr[1] & 0x7F)
	switch length {
	case 126:
		var ext [2]byte
		if _, err = io.ReadFull(c.br, ext[:]); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err = io.ReadFull(c.br, ext[:]); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint64(ext[:]))
	}
	if length < 0 || length > maxMessageSize {
		err = fmt.Errorf("ws: frame too large (%d)", length)
		return
	}
	var maskKey [4]byte
	if masked {
		if _, err = io.ReadFull(c.br, maskKey[:]); err != nil {
			return
		}
	}
	payload = make([]byte, length)
	if _, err = io.ReadFull(c.br, payload); err != nil {
		return
	}
	if masked {
		for i := range payload {
			payload[i] ^= maskKey[i&3]
		}
	}
	return
}

func (c *wsConn) writeFrame(op byte, payload []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	if closed {
		return errWSClosed
	}
	_ = c.c.SetWriteDeadline(time.Now().Add(15 * time.Second))
	var hdr [10]byte
	hdr[0] = 0x80 | op
	n := 2
	switch {
	case len(payload) < 126:
		hdr[1] = byte(len(payload))
	case len(payload) <= 0xFFFF:
		hdr[1] = 126
		binary.BigEndian.PutUint16(hdr[2:4], uint16(len(payload)))
		n = 4
	default:
		hdr[1] = 127
		binary.BigEndian.PutUint64(hdr[2:10], uint64(len(payload)))
		n = 10
	}
	if _, err := c.c.Write(hdr[:n]); err != nil {
		return err
	}
	if len(payload) > 0 {
		if _, err := c.c.Write(payload); err != nil {
			return err
		}
	}
	return nil
}

func (c *wsConn) writeClose(code uint16, reason string) error {
	buf := make([]byte, 2+len(reason))
	binary.BigEndian.PutUint16(buf, code)
	copy(buf[2:], reason)
	return c.writeFrame(opClose, buf)
}

// Close tears down the connection.
func (c *wsConn) Close() error {
	c.closeMu.Do(func() {
		c.mu.Lock()
		c.closed = true
		c.mu.Unlock()
		_ = c.writeClose(1000, "closing")
		_ = c.c.Close()
	})
	return nil
}

// setReadDeadline lets the session enforce liveness timeouts.
func (c *wsConn) setReadDeadline(t time.Time) { _ = c.c.SetReadDeadline(t) }

var _ = atomic.Bool{}
