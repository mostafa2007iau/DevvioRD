package ai.devvio.rd;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Tiny HTTP helper used by the file browser and clipboard REST endpoints.
 * No third-party dependencies; explicit timeouts everywhere.
 */
public final class Http {

    private Http() {
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static HttpURLConnection open(String method, String url, String token, int connectMs,
                                          int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("User-Agent", "DevvioRD-Android");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("X-DevvioRD-Token", token);
        }
        return c;
    }

    /** GET returning the body as a string (null on failure). */
    public static String text(String url, String token) {
        HttpURLConnection c = null;
        try {
            c = open("GET", url, token, 6000, 15000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return null;
            }
            return readAll(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** GET returning raw bytes (null on failure). */
    public static byte[] bytes(String url, String token) {
        HttpURLConnection c = null;
        try {
            c = open("GET", url, token, 6000, 20000);
            if (c.getResponseCode() >= 400) {
                return null;
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, c.getContentLength()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** POST a JSON body, returning the response body (null on failure). */
    public static String postJson(String url, String token, String json) {
        HttpURLConnection c = null;
        try {
            c = open("POST", url, token, 6000, 20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.flush();
            out.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return null;
            }
            return readAll(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** POST raw bytes (used for upload chunks). */
    public static int postBytes(String url, String token, byte[] data, int off, int len)
            throws IOException {
        HttpURLConnection c = null;
        try {
            c = open("POST", url, token, 8000, 30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(len);
            OutputStream out = c.getOutputStream();
            out.write(data, off, len);
            out.flush();
            out.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                readAll(in);
            }
            return code;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Downloads a URL to a file, reporting progress.
     *
     * @return true on success
     */
    public static boolean download(String url, String token, File dest, Progress p) {
        HttpURLConnection c = null;
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try {
            c = open("GET", url, token, 8000, 30000);
            if (c.getResponseCode() >= 400) {
                return false;
            }
            long total = c.getContentLength();
            InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null && System.currentTimeMillis() - lastReport > 120) {
                    lastReport = System.currentTimeMillis();
                    p.onProgress(done, total);
                }
            }
            out.flush();
            out.close();
            in.close();
            if (p != null) {
                p.onProgress(done, total > 0 ? total : done);
            }
            if (dest.exists() && !dest.delete()) {
                // overwrite below
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }
}
