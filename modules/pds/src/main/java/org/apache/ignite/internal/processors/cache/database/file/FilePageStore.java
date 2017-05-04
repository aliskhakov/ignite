package org.apache.ignite.internal.processors.cache.database.file;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.store.PageStore;
import org.apache.ignite.internal.processors.cache.database.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.database.wal.crc.IgniteDataIntegrityViolationException;
import org.apache.ignite.internal.processors.cache.database.wal.crc.PureJavaCrc32;
import org.apache.ignite.internal.util.typedef.internal.U;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_PDS_SKIP_CRC;

/**
 * File page store.
 */
public class FilePageStore implements PageStore {
    /** Page store file signature. */
    private static final long SIGNATURE = 0xF19AC4FE60C530B8L;

    /** File version. */
    private static final int VERSION = 1;

    /** Allocated field offset. */
    public static final int HEADER_SIZE = 8/*SIGNATURE*/ + 4/*VERSION*/ + 1/*type*/ + 4/*page size*/;

    /** */
    private final File cfgFile;

    /** */
    private final byte type;

    /** Database configuration. */
    private final MemoryConfiguration dbCfg;

    /** */
    private RandomAccessFile file;

    /** */
    private FileChannel ch;

    /** */
    private final AtomicLong allocated;

    /** */
    private final int pageSize;

    /** */
    private volatile boolean inited;

    /** */
    private volatile boolean recover;

    /** */
    private volatile int tag;

    /** */
    private boolean skipCrc = IgniteSystemProperties.getBoolean(IGNITE_PDS_SKIP_CRC, false);

    /** */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * @param file File.
     */
    public FilePageStore(byte type, File file, MemoryConfiguration cfg) {
        this.type = type;

        cfgFile = file;
        dbCfg = cfg;

        allocated = new AtomicLong();

        pageSize = dbCfg.getPageSize();
    }

    /** {@inheritDoc} */
    @Override public boolean exists() {
        return cfgFile.exists() && cfgFile.length() > HEADER_SIZE;
    }

    /**
     * @param type Type.
     * @param pageSize Page size.
     * @return Byte buffer instance.
     */
    public static ByteBuffer header(byte type, int pageSize) {
        ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        hdr.putLong(SIGNATURE);

        hdr.putInt(VERSION);

        hdr.put(type);

        hdr.putInt(pageSize);

        hdr.rewind();

        return hdr;
    }

    /**
     *
     */
    private long initFile() {
        try {
            ByteBuffer hdr = header(type, dbCfg.getPageSize());

            while (hdr.remaining() > 0)
                ch.write(hdr);
        }
        catch (IOException e) {
            throw new IgniteException("Check file failed.", e);
        }

        //there is 'super' page in every file
        return HEADER_SIZE + dbCfg.getPageSize();
    }

    /**
     *
     */
    private long checkFile() throws IgniteCheckedException {
        try {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            while (hdr.remaining() > 0)
                ch.read(hdr);

            hdr.rewind();

            long signature = hdr.getLong();

            if (SIGNATURE != signature)
                throw new IgniteCheckedException("Failed to verify store file (invalid file signature)" +
                    " [expectedSignature=" + U.hexLong(SIGNATURE) +
                    ", actualSignature=" + U.hexLong(signature) + ']');

            int ver = hdr.getInt();

            if (VERSION != ver)
                throw new IgniteCheckedException("Failed to verify store file (invalid file version)" +
                    " [expectedVersion=" + VERSION +
                    ", fileVersion=" + ver + "]");

            byte type = hdr.get();

            if (this.type != type)
                throw new IgniteCheckedException("Failed to verify store file (invalid file type)" +
                    " [expectedFileType=" + this.type +
                    ", actualFileType=" + type + "]");

            int pageSize = hdr.getInt();

            if (dbCfg.getPageSize() != pageSize)
                throw new IgniteCheckedException("Failed to verify store file (invalid page size)" +
                    " [expectedPageSize=" + dbCfg.getPageSize() +
                    ", filePageSize=" + pageSize + "]");

            long fileSize = file.length();

            if (fileSize == HEADER_SIZE) // Every file has a special meta page.
                fileSize = pageSize + HEADER_SIZE;

            if ((fileSize - HEADER_SIZE) % pageSize != 0)
                throw new IgniteCheckedException("Failed to verify store file (invalid file size)" +
                    " [fileSize=" + U.hexLong(fileSize) +
                    ", pageSize=" + U.hexLong(pageSize) + ']');

            return fileSize;
        }
        catch (IOException e) {
            throw new IgniteCheckedException("File check failed", e);
        }
    }

    /**
     * @param cleanFile {@code True} to delete file.
     * @throws IgniteCheckedException If failed.
     */
    public void stop(boolean cleanFile) throws IgniteCheckedException {
        lock.writeLock().lock();

        try {
            if (!inited)
                return;

            ch.force(false);

            file.close();

            if (cleanFile)
                cfgFile.delete();
        }
        catch (IOException e) {
            throw new IgniteCheckedException(e);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     *
     */
    public void truncate(int tag) throws IgniteCheckedException {
        lock.writeLock().lock();

        try {
            if (!inited)
                return;

            this.tag = tag;

            ch.position(0);

            file.setLength(0);

            allocated.set(initFile());
        }
        catch (IOException e) {
            throw new IgniteCheckedException(e);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     *
     */
    public void beginRecover() {
        lock.writeLock().lock();

        try {
            recover = true;
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     *
     */
    public void finishRecover() {
        lock.writeLock().lock();

        try {
            if (inited)
                allocated.set(ch.size());

            recover = false;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void read(long pageId, ByteBuffer pageBuf, boolean keepCrc) throws IgniteCheckedException {
        init();

        try {
            long off = pageOffset(pageId);

            assert pageBuf.capacity() == pageSize;
            assert pageBuf.position() == 0;
            assert pageBuf.order() == ByteOrder.nativeOrder();

            int len = pageSize;

            do {
                int n = ch.read(pageBuf, off);

                // If page was not written yet, nothing to read.
                if (n < 0) {
                    pageBuf.put(new byte[pageBuf.remaining()]);

                    return;
                }

                off += n;

                len -= n;
            }
            while (len > 0);

            int savedCrc32 = PageIO.getCrc(pageBuf);

            PageIO.setCrc(pageBuf, 0);

            pageBuf.position(0);

            if (!skipCrc) {
                int curCrc32 = PureJavaCrc32.calcCrc32(pageBuf, pageSize);

                if ((savedCrc32 ^ curCrc32) != 0)
                    throw new IgniteDataIntegrityViolationException("Failed to read page (CRC validation failed) " +
                        "[id=" + U.hexLong(pageId) + ", off=" + (off - pageSize) +
                        ", file=" + cfgFile.getAbsolutePath() + ", fileSize=" + ch.size() +
                        ", savedCrc=" + U.hexInt(savedCrc32) + ", curCrc=" + U.hexInt(curCrc32) + "]");
            }

            assert PageIO.getCrc(pageBuf) == 0;

            if (keepCrc)
                PageIO.setCrc(pageBuf, savedCrc32);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Read error", e);
        }
    }

    /** {@inheritDoc} */
    @Override public void readHeader(ByteBuffer buf) throws IgniteCheckedException {
        init();

        try {
            assert buf.remaining() == HEADER_SIZE;

            int len = HEADER_SIZE;

            long off = 0;

            do {
                int n = ch.read(buf, off);

                // If page was not written yet, nothing to read.
                if (n < 0)
                    return;

                off += n;

                len -= n;
            }
            while (len > 0);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Read error", e);
        }
    }

    /**
     * @throws IgniteCheckedException If failed to initialize store file.
     */
    private void init() throws IgniteCheckedException {
        if (!inited) {
            lock.writeLock().lock();

            try {
                if (!inited) {
                    RandomAccessFile rndFile = null;

                    IgniteCheckedException err = null;

                    try {
                        file = rndFile = new RandomAccessFile(cfgFile, "rw");

                        ch = file.getChannel();

                        if (file.length() == 0)
                            allocated.set(initFile());
                        else
                            allocated.set(checkFile());

                        inited = true;
                    }
                    catch (IOException e) {
                        throw err = new IgniteCheckedException("Can't open file: " + cfgFile.getName(), e);
                    }
                    finally {
                        if (err != null && rndFile != null)
                            try {
                                rndFile.close();
                            }
                            catch (IOException e) {
                                err.addSuppressed(e);
                            }
                    }
                }
            }
            finally {
                lock.writeLock().unlock();
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void write(long pageId, ByteBuffer pageBuf, int tag) throws IgniteCheckedException {
        init();

        lock.readLock().lock();

        try {
            if (tag < this.tag)
                return;

            long off = pageOffset(pageId);

            assert (off >= 0 && off + pageSize <= allocated.get() + HEADER_SIZE) || recover :
                "off=" + U.hexLong(off) + ", allocated=" + U.hexLong(allocated.get()) + ", pageId=" + U.hexLong(pageId);

            assert pageBuf.capacity() == pageSize;
            assert pageBuf.position() == 0;
            assert pageBuf.order() == ByteOrder.nativeOrder();

            int len = pageSize;

            assert PageIO.getCrc(pageBuf) == 0 : U.hexLong(pageId);

            int crc32 = skipCrc ? 0 : PureJavaCrc32.calcCrc32(pageBuf, pageSize);

            PageIO.setCrc(pageBuf, crc32);

            pageBuf.position(0);

            do {
                int n = ch.write(pageBuf, off);

                off += n;

                len -= n;
            }
            while (len > 0);

            PageIO.setCrc(pageBuf, 0);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to write the page to the file store [pageId=" + pageId +
                ", file=" + cfgFile.getAbsolutePath() + ']', e);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public long pageOffset(long pageId) {
        return (long) PageIdUtils.pageIndex(pageId) * pageSize + HEADER_SIZE;
    }

    /** {@inheritDoc} */
    @Override public void sync() throws IgniteCheckedException {
        lock.writeLock().lock();

        try {
            init();

            ch.force(false);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Sync error", e);
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized void ensure() throws IgniteCheckedException {
        init();
    }

    /** {@inheritDoc} */
    @Override public long allocatePage() throws IgniteCheckedException {
        init();

        long off = allocPage();

        return off / pageSize;
    }

    /**
     *
     */
    private long allocPage() {
        long off;

        do {
            off = allocated.get();

            if (allocated.compareAndSet(off, off + pageSize))
                break;
        }
        while (true);

        return off;
    }

    /** {@inheritDoc} */
    @Override public int pages() {
        if (!inited)
            return 0;

        return (int)(allocated.get() / pageSize);
    }

    /**
     * Visible for testing
     */
    FileChannel getCh() {
        return ch;
    }
}
