package cli

import (
	"fmt"
	"strings"
)

// A compact QR code encoder (byte mode, error-correction level L, versions 1-5,
// automatic mask selection), implemented from ISO/IEC 18004 so the server needs
// no third-party dependency just to print a pairing code.
//
// Scope is deliberately small: versions 1-5 at level L are single-block codes,
// which keeps the Reed-Solomon step simple while still holding ~106 bytes - far
// more than a pairing URL needs. The output is validated by an independent
// decoder in scripts/qr_verify.py.

// EC level bits as they appear in the format information (L = 01).
const ecLevelL = 1

// Single-block parameters for versions 1-5 at EC level L.
type versionInfo struct {
	version       int
	dataCodewords int
	ecCodewords   int
	alignmentPos  int // -1 when the version has no alignment pattern
}

var versionsL = []versionInfo{
	{1, 19, 7, -1},
	{2, 34, 10, 18},
	{3, 55, 15, 22},
	{4, 80, 20, 26},
	{5, 108, 26, 30},
}

const maxVersion = 5

type qrCode struct {
	size    int
	modules [][]bool
}

// QR renders text as terminal lines using half-block glyphs. Returns nil when
// the text does not fit in the supported versions.
func QR(text string) []string {
	q, err := encodeQR(text)
	if err != nil {
		return nil
	}
	return renderQR(q)
}

func renderQR(q *qrCode) []string {
	const quiet = 2
	var lines []string
	for y := -quiet; y < q.size+quiet; y += 2 {
		var sb strings.Builder
		for x := -quiet; x < q.size+quiet; x++ {
			top := moduleAt(q, x, y)
			bot := moduleAt(q, x, y+1)
			switch {
			case top && bot:
				sb.WriteString("█")
			case top:
				sb.WriteString("▀")
			case bot:
				sb.WriteString("▄")
			default:
				sb.WriteString(" ")
			}
		}
		lines = append(lines, sb.String())
	}
	return lines
}

func moduleAt(q *qrCode, x, y int) bool {
	if x < 0 || y < 0 || x >= q.size || y >= q.size {
		return false
	}
	return q.modules[y][x]
}

func encodeQR(text string) (*qrCode, error) {
	data := []byte(text)
	for _, v := range versionsL {
		// 4 bits mode + 8 bits length (versions 1-9) + payload
		if 4+8+len(data)*8 <= v.dataCodewords*8 {
			return buildQR(data, v)
		}
	}
	return nil, fmt.Errorf("qr: payload too long (%d bytes, max %d)",
		len(data), (versionsL[maxVersion-1].dataCodewords*8-12)/8)
}

func buildQR(data []byte, vi versionInfo) (*qrCode, error) {
	size := 17 + 4*vi.version
	q := &qrCode{size: size, modules: make([][]bool, size)}
	reserved := make([][]bool, size)
	for i := range q.modules {
		q.modules[i] = make([]bool, size)
		reserved[i] = make([]bool, size)
	}
	set := func(x, y int, dark bool) {
		if x < 0 || y < 0 || x >= size || y >= size {
			return
		}
		q.modules[y][x] = dark
		reserved[y][x] = true
	}

	// --- finder patterns with separators ---
	drawFinder := func(cx, cy int) {
		for dy := -4; dy <= 4; dy++ {
			for dx := -4; dx <= 4; dx++ {
				ax, ay := abs(dx), abs(dy)
				ring := max(ax, ay)
				dark := ring == 3 || ring <= 1
				if ring == 4 {
					dark = false // separator
				}
				set(cx+dx, cy+dy, dark)
			}
		}
	}
	drawFinder(3, 3)
	drawFinder(size-4, 3)
	drawFinder(3, size-4)

	// --- timing patterns ---
	for i := 8; i < size-8; i++ {
		dark := i%2 == 0
		set(i, 6, dark)
		set(6, i, dark)
	}

	// --- alignment pattern (single, bottom-right, versions 2-5) ---
	if vi.alignmentPos > 0 {
		cx, cy := vi.alignmentPos, vi.alignmentPos
		for dy := -2; dy <= 2; dy++ {
			for dx := -2; dx <= 2; dx++ {
				ax, ay := abs(dx), abs(dy)
				set(cx+dx, cy+dy, max(ax, ay) != 1)
			}
		}
	}

	// --- reserve format/version areas so data placement skips them ---
	for i := 0; i <= 8; i++ {
		reserved[8][i] = true
		reserved[i][8] = true
	}
	for i := 0; i < 8; i++ {
		reserved[8][size-1-i] = true
		reserved[size-1-i][8] = true
	}
	// dark module
	set(8, size-8, true)

	// --- data + error correction ---
	codewords, err := buildCodewords(encodeDataBits(data, vi), vi)
	if err != nil {
		return nil, err
	}
	placeData(q, reserved, codewords)

	// --- choose the mask with the lowest penalty ---
	bestScore := 1 << 30
	var best [][]bool
	for mask := 0; mask < 8; mask++ {
		cand := cloneMatrix(q.modules)
		applyMask(cand, reserved, size, mask)
		drawFormat(cand, size, ecLevelL, mask)
		if s := penalty(cand, size); s < bestScore {
			bestScore = s
			best = cand
		}
	}
	if best == nil {
		return nil, fmt.Errorf("qr: mask selection failed")
	}
	q.modules = best
	return q, nil
}

func cloneMatrix(m [][]bool) [][]bool {
	out := make([][]bool, len(m))
	for i := range m {
		out[i] = append([]bool(nil), m[i]...)
	}
	return out
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// encodeDataBits builds the bit stream: mode 0100, length, payload, terminator.
func encodeDataBits(data []byte, vi versionInfo) []bool {
	capacity := vi.dataCodewords * 8
	bits := make([]bool, 0, capacity)
	appendBits := func(v uint32, n int) {
		for i := n - 1; i >= 0; i-- {
			bits = append(bits, (v>>uint(i))&1 == 1)
		}
	}
	appendBits(0b0100, 4)            // byte mode
	appendBits(uint32(len(data)), 8) // length (versions 1-9 use 8 bits)
	for _, b := range data {
		appendBits(uint32(b), 8)
	}
	// terminator
	for i := 0; i < 4 && len(bits) < capacity; i++ {
		bits = append(bits, false)
	}
	// pad to a byte boundary
	for len(bits)%8 != 0 {
		bits = append(bits, false)
	}
	// pad codewords 0xEC / 0x11
	for i := 0; len(bits) < capacity; i++ {
		pad := uint32(0xEC)
		if i%2 == 1 {
			pad = 0x11
		}
		appendBits(pad, 8)
	}
	return bits[:capacity]
}

// buildCodewords converts the data bits to codewords and appends EC codewords.
func buildCodewords(bits []bool, vi versionInfo) ([]byte, error) {
	data := make([]byte, vi.dataCodewords)
	for i := 0; i < len(bits) && i < len(data)*8; i++ {
		if bits[i] {
			data[i/8] |= 1 << uint(7-i%8)
		}
	}
	ec := rsEncode(data, vi.ecCodewords)
	out := make([]byte, 0, len(data)+len(ec))
	out = append(out, data...)
	out = append(out, ec...)
	return out, nil
}

// --- Reed-Solomon over GF(256), polynomial 0x11D ---

var gfExp [512]byte
var gfLog [256]int

func init() {
	x := 1
	for i := 0; i < 255; i++ {
		gfExp[i] = byte(x)
		gfLog[x] = i
		x <<= 1
		if x&0x100 != 0 {
			x ^= 0x11D
		}
	}
	for i := 255; i < 512; i++ {
		gfExp[i] = gfExp[i-255]
	}
}

func gfMul(a, b byte) byte {
	if a == 0 || b == 0 {
		return 0
	}
	return gfExp[gfLog[a]+gfLog[b]]
}

// rsGeneratorPoly returns the generator polynomial coefficients (degree n,
// without the leading 1) for RS(n, k).
func rsGeneratorPoly(degree int) []byte {
	result := []byte{1}
	for i := 0; i < degree; i++ {
		next := make([]byte, len(result)+1)
		for j, c := range result {
			next[j] ^= c                    // c * x
			next[j+1] ^= gfMul(c, gfExp[i]) // c * alpha^i
		}
		result = next
	}
	return result[1:]
}

func rsEncode(data []byte, ecLen int) []byte {
	gen := rsGeneratorPoly(ecLen)
	ec := make([]byte, ecLen)
	for _, b := range data {
		factor := b ^ ec[0]
		copy(ec, ec[1:])
		ec[ecLen-1] = 0
		for i := 0; i < ecLen; i++ {
			ec[i] ^= gfMul(gen[i], factor)
		}
	}
	return ec
}

// placeData writes codewords in the standard zig-zag order.
func placeData(q *qrCode, reserved [][]bool, cw []byte) {
	size := q.size
	bitIdx := 0
	total := len(cw) * 8
	upward := true
	for col := size - 1; col > 0; col -= 2 {
		if col == 6 {
			col-- // skip the vertical timing pattern column
		}
		for i := 0; i < size; i++ {
			y := i
			if upward {
				y = size - 1 - i
			}
			for c := 0; c < 2; c++ {
				x := col - c
				if reserved[y][x] {
					continue
				}
				dark := false
				if bitIdx < total {
					dark = (cw[bitIdx/8]>>uint(7-bitIdx%8))&1 == 1
					bitIdx++
				}
				q.modules[y][x] = dark
			}
		}
		upward = !upward
	}
}

// applyMask XORs the mask pattern onto data modules only.
func applyMask(m [][]bool, reserved [][]bool, size, mask int) {
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			if reserved[y][x] {
				continue
			}
			var on bool
			switch mask {
			case 0:
				on = (x+y)%2 == 0
			case 1:
				on = y%2 == 0
			case 2:
				on = x%3 == 0
			case 3:
				on = (x+y)%3 == 0
			case 4:
				on = (y/2+x/3)%2 == 0
			case 5:
				on = (x*y)%2+(x*y)%3 == 0
			case 6:
				on = ((x*y)%2+(x*y)%3)%2 == 0
			case 7:
				on = ((x+y)%2+(x*y)%3)%2 == 0
			}
			if on {
				m[y][x] = !m[y][x]
			}
		}
	}
}

// drawFormat writes the 15-bit format information (BCH protected).
func drawFormat(m [][]bool, size, ecLevel, mask int) {
	data := ecLevel<<3 | mask
	rem := data
	for i := 0; i < 10; i++ {
		rem = (rem << 1) ^ ((rem >> 9) * 0x537)
	}
	bits := (data<<10 | rem) ^ 0x5412
	bit := func(i int) bool { return (bits>>uint(i))&1 == 1 }
	put := func(x, y int, i int) {
		if x < 0 || y < 0 || x >= size || y >= size {
			return
		}
		m[y][x] = bit(i)
	}
	// first copy, around the top-left finder
	for i := 0; i <= 5; i++ {
		put(8, i, i)
	}
	put(8, 7, 6)
	put(8, 8, 7)
	put(7, 8, 8)
	for i := 9; i <= 14; i++ {
		put(14-i, 8, i)
	}
	// second copy
	for i := 0; i <= 7; i++ {
		put(size-1-i, 8, i)
	}
	for i := 8; i <= 14; i++ {
		put(8, size-15+i, i)
	}
	m[size-8][8] = true // dark module
}

// penalty implements the four mask evaluation rules from the spec.
func penalty(m [][]bool, size int) int {
	score := 0
	// Rule 1: runs of five or more identical modules.
	for y := 0; y < size; y++ {
		run := 1
		for x := 1; x < size; x++ {
			if m[y][x] == m[y][x-1] {
				run++
			} else {
				if run >= 5 {
					score += run - 2
				}
				run = 1
			}
		}
		if run >= 5 {
			score += run - 2
		}
	}
	for x := 0; x < size; x++ {
		run := 1
		for y := 1; y < size; y++ {
			if m[y][x] == m[y-1][x] {
				run++
			} else {
				if run >= 5 {
					score += run - 2
				}
				run = 1
			}
		}
		if run >= 5 {
			score += run - 2
		}
	}
	// Rule 2: 2x2 blocks of the same colour.
	for y := 0; y < size-1; y++ {
		for x := 0; x < size-1; x++ {
			c := m[y][x]
			if c == m[y][x+1] && c == m[y+1][x] && c == m[y+1][x+1] {
				score += 3
			}
		}
	}
	// Rule 3: finder-like patterns (1:1:3:1:1 with 4 light modules).
	finderLike := []bool{true, false, true, true, true, false, true, false, false, false, false}
	countPattern := func(get func(int) bool, n int) int {
		count := 0
		for i := 0; i+11 <= n; i++ {
			match := true
			for j := 0; j < 11; j++ {
				if get(i+j) != finderLike[j] {
					match = false
					break
				}
			}
			if match {
				count++
			}
			// the mirrored variant
			match = true
			for j := 0; j < 11; j++ {
				if get(i+j) != finderLike[10-j] {
					match = false
					break
				}
			}
			if match {
				count++
			}
		}
		return count
	}
	for y := 0; y < size; y++ {
		row := y
		score += 40 * countPattern(func(x int) bool { return m[row][x] }, size)
	}
	for x := 0; x < size; x++ {
		col := x
		score += 40 * countPattern(func(y int) bool { return m[y][col] }, size)
	}
	// Rule 4: deviation of the dark-module ratio from 50%.
	dark := 0
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			if m[y][x] {
				dark++
			}
		}
	}
	total := size * size
	pct := dark * 100 / total
	dev := pct - 50
	if dev < 0 {
		dev = -dev
	}
	score += dev / 5 * 10
	return score
}
