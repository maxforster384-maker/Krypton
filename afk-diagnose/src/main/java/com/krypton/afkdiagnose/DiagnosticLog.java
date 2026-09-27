package com.krypton.afkdiagnose;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Begrenztes, nicht blockierendes Protokoll. Ausschliesslich dieser Thread schreibt Dateien. */
final class DiagnosticLog implements AutoCloseable {
    static final int HISTORY_LIMIT = 500;
    static final long FILE_LIMIT = 4L * 1024 * 1024;
    private final Path path;
    private final Consumer<Exception> errorSink;
    private final long fileLimit;
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(256);
    private final AtomicInteger dropped = new AtomicInteger();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final Thread writer;
    private volatile List<String> visible = List.of();
    private volatile boolean running = true;
    private volatile boolean diskFailed;

    DiagnosticLog(Path path, Consumer<Exception> errorSink) {
        this(path, errorSink, FILE_LIMIT);
    }

    DiagnosticLog(Path path, Consumer<Exception> errorSink, long fileLimit) {
        this.path = path;
        this.errorSink = errorSink;
        this.fileLimit = fileLimit;
        writer = new Thread(this::runWriter, "AFK-Diagnose-Protokoll");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    void record(String event, String detail) {
        String line = OffsetDateTime.now() + " | " + clean(event) + " | " + clean(detail);
        if (!running || !pending.offer(line)) dropped.incrementAndGet();
    }

    static String clean(String text) {
        if (text == null) return "unbekannt";
        String safe = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                .replaceAll("\\p{Cntrl}", " ");
        return safe.length() > 1200 ? safe.substring(0, 1200) + "..." : safe;
    }

    List<String> history() { return visible; }
    boolean diskFailed() { return diskFailed; }
    int dropped() { return dropped.get(); }
    Path path() { return path; }

    private void runWriter() {
        loadTail();
        while (running || !pending.isEmpty()) {
            try {
                String first = pending.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<String> batch = new ArrayList<>();
                batch.add(first);
                pending.drainTo(batch, 31);
                for (String line : batch) {
                    history.addFirst(line);
                    while (history.size() > HISTORY_LIMIT) history.removeLast();
                }
                visible = List.copyOf(history);
                if (!diskFailed) append(batch);
            } catch (InterruptedException ignored) {
                // Fremde Unterbrechungen betreffen nur diesen Hintergrund-Thread.
            } catch (Exception problem) {
                reportDiskFailure(problem);
            }
        }
    }

    private void loadTail() {
        if (!Files.isRegularFile(path)) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long start = Math.max(0, channel.size() - 256 * 1024);
            channel.position(start);
            ByteBuffer bytes = ByteBuffer.allocate((int) (channel.size() - start));
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            bytes.flip();
            String[] lines = StandardCharsets.UTF_8.decode(bytes).toString().split("\\R");
            // Ein mitten in einer Zeile beginnender Ausschnitt ist kein echter Eintrag.
            for (int i = start > 0 ? 1 : 0; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                history.addFirst(clean(lines[i]));
                while (history.size() > HISTORY_LIMIT) history.removeLast();
            }
            visible = List.copyOf(history);
        } catch (Exception problem) {
            // Ein Lesefehler darf weder Minecraft noch Krypton abbrechen.
            reportDiskFailure(problem);
        }
    }

    private void append(List<String> batch) throws IOException {
        byte[] bytes = (String.join(System.lineSeparator(), batch) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path) && Files.size(path) + bytes.length > fileLimit) {
            Path previous = path.resolveSibling(path.getFileName() + ".1");
            Files.move(path, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void reportDiskFailure(Exception problem) {
        if (diskFailed) return;
        diskFailed = true;
        try { errorSink.accept(problem); } catch (Exception ignored) { }
    }

    @Override
    public void close() {
        running = false;
        // Kein interrupt(): Das koennte einen laufenden FileChannel-Lesezugriff
        // abbrechen. poll() wartet hoechstens 250 ms; Minecraft wartet gar nicht.
    }

    // Nur Tests duerfen auf den Writer warten, niemals ein Minecraft-Callback.
    boolean awaitStopped(long millis) throws InterruptedException {
        writer.join(millis);
        return !writer.isAlive();
    }
}
