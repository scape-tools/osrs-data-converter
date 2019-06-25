package io.nshusa.app.osdc.other.osrscd.net;

import io.nshusa.app.osdc.other.osrscd.CacheDownloader;
import io.nshusa.app.osdc.other.osrscd.cache.FileStore;
import io.nshusa.app.osdc.other.osrscd.cache.ReferenceTable;
import io.nshusa.app.osdc.other.osrscd.hash.Whirlpool;
import javafx.concurrent.Task;
import net.openrs.util.ByteBufferUtils;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Manages the requesting of files from the game server.
 *
 * @author Method
 * @author SapphirusBeryl
 */
public class CacheRequester extends Task<Boolean> {

    public enum State {
        DISCONNECTED, ERROR, OUTDATED, CONNECTING, CONNECTED
    }

    private Queue<FileRequest> requests;
    private Map<Long, FileRequest> waiting;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private State state;
    private String host;
    private int revision = 168;
    private FileRequest current;
    private long lastUpdate;
    private ByteBuffer outputBuffer;
    private ByteBuffer inputBuffer;

    private ReferenceTable versionTable;
    private ReferenceTable[] tables;
    private ReferenceTable[] oldTables;
    private FileStore reference;
    private FileStore[] stores;

    /**
     * Creates a new CacheRequester instance.
     */
    public CacheRequester() {
        requests = new LinkedList<>();
        waiting = new HashMap<>();
        state = State.DISCONNECTED;
        outputBuffer = ByteBuffer.allocate(4);
        inputBuffer = ByteBuffer.allocate(8);
    }

    @Override
    protected Boolean call() throws Exception {
        try {
            connect();
            downloadVersionTable();
            initCacheIndices(versionTable.getEntryCount());
            initOldTables();
            downloadNewTables();
            update();
        } finally {
            if (stores != null) {
                for (FileStore store : stores) {
                    if (store == null) {
                        continue;
                    }
                    store.close();
                }
            }
        }
        return false;
    }

    private void connect() {
        connect(String.format("oldschool%d.runescape.com", 18), getRevision());
        while (getCurrentState() != CacheRequester.State.CONNECTED) {

            if (getCurrentState() == State.OUTDATED) {
                updateMessage(String.format("Requesting=%d", getRevision()));
            }

            process();
        }
        updateMessage("Successful connection");
    }

    private void downloadVersionTable() {
        FileRequest mainRequest = request(255, 255);
        while (!mainRequest.isComplete()) {
            process();
        }
        versionTable = new ReferenceTable(mainRequest.getBuffer());
    }

    private void initCacheIndices(int count) {
        try {
            File outputDir = new File("./dump/caches/#" + getRevision());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            RandomAccessFile dataFile = new RandomAccessFile(new File(outputDir, "main_file_cache.dat2"), "rw");
            RandomAccessFile referenceFile = new RandomAccessFile(new File(outputDir, "main_file_cache.idx255"), "rw");
            reference = new FileStore(255, dataFile.getChannel(), referenceFile.getChannel(), 0x7a120);
            stores = new FileStore[count];
            for (int i = 0; i < count; i++) {
                RandomAccessFile indexFile = new RandomAccessFile(new File(outputDir, "main_file_cache.idx" + i), "rw");
                stores[i] = new FileStore(i, dataFile.getChannel(), indexFile.getChannel(), 0xf4240);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void initOldTables() {
        oldTables = new ReferenceTable[reference.getFileCount()];
        for (int i = 0; i < oldTables.length; i++) {
            ByteBuffer data = reference.get(i);
            if (data != null) {
                oldTables[i] = new ReferenceTable(i, data, null);
            }
        }
    }

    private void downloadNewTables() {
        List<Integer> changes = CacheDownloader.findTableChanges(versionTable, oldTables);
        Queue<FileRequest> requests = new LinkedList<>();
        tables = new ReferenceTable[versionTable.getEntryCount()];
        for (int i = 0; i < changes.size(); i++) {
            requests.offer(request(255, changes.get(i)));
        }

        while (requests.size() > 0) {
            process();
            for (Iterator<FileRequest> iter = requests.iterator(); iter.hasNext(); ) {
                FileRequest request = iter.next();
                if (request.isComplete()) {
                    int file = request.getFile();
                    ByteBuffer data = request.getBuffer();
                    tables[file] = new ReferenceTable(file, data, versionTable);

                    data.position(0);
                    reference.put(file, data, data.capacity());
                    iter.remove();
                }
            }
        }
    }

    private void update() {
        for (int i = 0; i < versionTable.getEntryCount(); i++) {
            List<Integer> changes = CacheDownloader.findFileChanges(i, versionTable, stores, tables, oldTables);
            if (changes == null || changes.size() == 0) {
                continue;
            }

            ReferenceTable table = tables[i] != null ? tables[i] : oldTables[i];
            CRC32 crc = new CRC32();

            Queue<FileRequest> requests = new LinkedList<>();
            for (int j = 0; j < changes.size(); j++) {
                requests.offer(request(i, changes.get(j)));
            }
            while (requests.size() > 0) {
                process();
                for (Iterator<FileRequest> iter = requests.iterator(); iter.hasNext(); ) {
                    FileRequest request = iter.next();
                    if (request.isComplete()) {
                        int file = request.getFile();
                        ByteBuffer data = request.getBuffer();
                        ReferenceTable.Entry entry = table.getEntry(file);

                        crc.update(data.array(), 0, data.limit());
                        if (entry.getCRC() != (int) crc.getValue()) {
                            throw new RuntimeException("CRC mismatch " + i + "," + file + "," + entry.getCRC() + " - " + (int) crc.getValue());
                        }
                        crc.reset();

                        byte[] entryDigest = entry.getDigest();
                        if (entryDigest != null) {
                            byte[] digest = Whirlpool.whirlpool(data.array(), 0, data.limit());
                            for (int j = 0; j < 64; j++) {
                                if (digest[j] != entryDigest[j]) {
                                    throw new RuntimeException("Digest mismatch " + i + "," + file);
                                }
                            }
                        }

                        int version = entry.getVersion();
                        data.position(data.limit()).limit(data.capacity());
                        data.put((byte) (version >>> 8));
                        data.put((byte) version);
                        data.flip();

                        stores[i].put(file, data, data.capacity());
                        iter.remove();
                    }
                }
            }
        }
    }

    /**
     * Connects to the specified host on port 43594 and initiates the update
     * protocol handshake.
     *
     * @param host
     *            The world to connect to
     * @param major
     *            The client's revision
     */
    public void connect(String host, int major) {
        this.host = host;
        this.revision = major;

        try {
            socket = new Socket(host, 43594);
            input = socket.getInputStream();
            output = socket.getOutputStream();

            ByteBuffer buffer = ByteBuffer.allocate(5);
            buffer.put((byte) 15); // handshake type
            buffer.putInt(major); // client's revision version
            output.write(buffer.array());
            output.flush();

            state = State.CONNECTING;
        } catch (IOException ioex) {
            ioex.printStackTrace();
        }
    }

    /**
     * Submits a request to be sent to the server.
     *
     * @param index
     *            The cache index the file belongs to
     * @param file
     *            The file number
     * @return A FileRequest object representing the requested file.
     */
    public FileRequest request(int index, int file) {
        FileRequest request = new FileRequest(index, file);
        requests.offer(request);
        return request;
    }

    /**
     * Gets the current state of the requester.
     *
     * @return The requester's current
     */
    public State getCurrentState() {
        return state;
    }

    /**
     * Handles the bulk of the processing for the requester. This method uses
     * the current state of the requester to choose the correct action.
     * <p/>
     * When connected, this method will send up to 20 requests to the server at
     * one time, reading and processing them as they are sent back from the
     * server.
     */
    public void process() {
        if (state == State.CONNECTING) {
            try {
                if (input.available() > 0) {
                    int response = input.read();
                    if (response == 0) {
                        sendConnectionInfo();
                        lastUpdate = System.currentTimeMillis();
                        state = State.CONNECTED;
                    } else if (response == 6) {
                        state = State.OUTDATED;
                    } else {
                        state = State.ERROR;
                    }
                }
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        } else if (state == State.OUTDATED) {
            reset();
            connect(host, ++revision);
        } else if (state == State.ERROR) {
            throw new RuntimeException("Unexpected server response");
        } else if (state == State.DISCONNECTED) {
            reset();
            connect(host, revision);
        } else {
            if (lastUpdate != 0 && System.currentTimeMillis() - lastUpdate > 30000) {
                System.out.println("Server timeout, dropping connection");
                state = State.DISCONNECTED;
                return;
            }
            try {
                while (!requests.isEmpty() && waiting.size() < 20) {
                    FileRequest request = requests.poll();
                    outputBuffer.put(request.getIndex() == 255 ? (byte) 1 : (byte) 0);
                    ByteBufferUtils.putMedium(outputBuffer, (int) request.hash());
                    output.write(outputBuffer.array());
                    output.flush();
                    outputBuffer.clear();

                    if (versionTable != null) {
                        double progress = (double)(request.getIndex() + 1) / versionTable.getEntryCount() * 100;
                        updateMessage(String.format("Fetching file=%d from index=%d %.2f%s", request.getFile(), request.getIndex(), progress, "%"));
                        updateProgress(request.getIndex(), versionTable.getEntryCount());
                    } else {
                        updateMessage(String.format("Fetching file=%d from index=%d", request.getFile(), request.getIndex()));
                    }
                    waiting.put(request.hash(), request);
                }
                for (int i = 0; i < 100; i++) {
                    int available = input.available();
                    if (available < 0)
                        throw new IOException();
                    if (available == 0)
                        break;
                    lastUpdate = System.currentTimeMillis();
                    int needed = 0;
                    if (current == null)
                        needed = 8;
                    else if (current.getPosition() == 0)
                        needed = 1;
                    if (needed > 0) {
                        if (available >= needed) {
                            if (current == null) {
                                inputBuffer.clear();
                                input.read(inputBuffer.array());
                                int index = inputBuffer.get() & 0xff;
                                int file = inputBuffer.getShort() & 0xffff;
                                int compression = (inputBuffer.get() & 0xff) & 0x7f;
                                int fileSize = inputBuffer.getInt();
                                long hash = ((long) index << 16) | file;
                                current = waiting.get(hash);
                                if (current == null) {
                                    throw new IOException();
                                }

                                int size = fileSize + (compression == 0 ? 5 : 9) + (index != 255 ? 2 : 0);
                                current.setSize(size);
                                ByteBuffer buffer = current.getBuffer();
                                buffer.put((byte) compression);
                                buffer.putInt(fileSize);
                                current.setPosition(8);
                                inputBuffer.clear();
                            } else if (current.getPosition() == 0) {
                                if (input.read() != 0xff) {
                                    current = null;
                                } else {
                                    current.setPosition(1);
                                }
                            } else {
                                throw new IOException();
                            }
                        }
                    } else {
                        ByteBuffer buffer = current.getBuffer();
                        int totalSize = buffer.capacity() - (current.getIndex() != 255 ? 2 : 0);
                        int blockSize = 512 - current.getPosition();
                        int remaining = totalSize - buffer.position();
                        if (remaining < blockSize)
                            blockSize = remaining;
                        if (available < blockSize)
                            blockSize = available;
                        int read = input.read(buffer.array(), buffer.position(), blockSize);
                        buffer.position(buffer.position() + read);
                        current.setPosition(current.getPosition() + read);
                        if (buffer.position() == totalSize) {
                            current.setComplete(true);
                            waiting.remove(current.hash());
                            buffer.flip();
                            current = null;
                        } else {
                            if (current.getPosition() == 512) {
                                current.setPosition(0);
                            }
                        }
                    }
                }
            } catch (IOException exception) {
                exception.printStackTrace();
                sendConnectionInfo();
                state = State.DISCONNECTED;
                System.exit(1);
            }
        }
    }

    /**
     * Sends the initial connection status and login packets to the server. By
     * default, this downloader indicates that it is logged out.
     */
    private void sendConnectionInfo() {
        try {
            outputBuffer.put((byte) 3);
            ByteBufferUtils.putMedium(outputBuffer, 0);
            output.write(outputBuffer.array());
            output.flush();
            outputBuffer.clear();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    /**
     * Resets the state of the requester. Files that have been sent and are
     * waiting to be processed will be requested again once the connection is
     * reestablished.
     */
    private void reset() {
        try {
            for (FileRequest request : waiting.values()) {
                requests.offer(request);
            }
            waiting.clear();
            socket.close();
            socket = null;
            input = null;
            output = null;
            current = null;
            lastUpdate = 0;
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public int getRevision() {
        return revision;
    }

}
