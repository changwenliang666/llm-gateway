package com.llmgateway.support;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 先写出第一块响应，停一会儿，再写出第二块。
 * 用来确认网关是边读边写，而不是等上游结束后一次返回。
 */
public final class PausingHttpServer implements AutoCloseable {

    private final ServerSocket socket;
    private Thread thread;

    public PausingHttpServer() throws IOException {
        this.socket = new ServerSocket(0);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void serve(String firstChunk, String secondChunk, long pauseMillis) {
        thread = new Thread(() -> {
            try (Socket accepted = socket.accept()) {
                readRequest(accepted.getInputStream());
                OutputStream output = accepted.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                        + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                writeChunk(output, firstChunk);
                output.flush();
                Thread.sleep(pauseMillis);
                writeChunk(output, secondChunk);
                output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
        thread.start();
    }

    public void await() throws InterruptedException {
        if (thread != null) {
            thread.join(10_000L);
        }
    }

    private static void readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        byte[] boundary = {'\r', '\n', '\r', '\n'};
        while (matched < boundary.length) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("上游测试服务器没有读完请求头");
            }
            header.write(value);
            if (value == boundary[matched]) {
                matched++;
            } else {
                matched = value == boundary[0] ? 1 : 0;
            }
        }
        int length = 0;
        String text = header.toString(StandardCharsets.UTF_8);
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        input.readNBytes(length);
    }

    private static void writeChunk(OutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
