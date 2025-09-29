/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.sftp.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.MapEntryUtils;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class FileHandle extends Handle {
    private final int access;
    private final SeekableByteChannel fileChannel;
    private final List<FileLock> locks = new ArrayList<>();
    private final Set<StandardOpenOption> openOptions;
    private final Collection<FileAttribute<?>> fileAttributes;

    public FileHandle(SftpSubsystem subsystem, Path file, String handle, int flags, int access, Map<String, Object> attrs)
            throws IOException {
        super(subsystem, file, handle);

        Set<StandardOpenOption> options = getOpenOptions(flags, access);
        // We are going to open a channel. If neither APPEND nor WRITE are explicitly specified, the default will be
        // READ.
        if (!options.contains(StandardOpenOption.WRITE) && !options.contains(StandardOpenOption.APPEND)) {
            options.add(StandardOpenOption.READ);
        }
        // Java cannot do READ | WRITE | APPEND; it throws an IllegalArgumentException "READ+APPEND not allowed". So
        // just open READ | WRITE, and use the ACE4_APPEND_DATA access flag to indicate that we need to handle "append"
        // mode ourselves. ACE4_APPEND_DATA should only have an effect if the file is indeed opened for APPEND mode.
        int desiredAccess = access & ~SftpConstants.ACE4_APPEND_DATA;
        if (options.contains(StandardOpenOption.APPEND)) {
            desiredAccess |= SftpConstants.ACE4_APPEND_DATA | SftpConstants.ACE4_WRITE_DATA
                             | SftpConstants.ACE4_WRITE_ATTRIBUTES;
            options.add(StandardOpenOption.WRITE);
            options.remove(StandardOpenOption.APPEND);
        }
        this.access = desiredAccess;
        this.openOptions = Collections.unmodifiableSet(options);
        this.fileAttributes = Collections.unmodifiableCollection(toFileAttributes(attrs));
        signalHandleOpening();

        FileAttribute<?>[] fileAttrs = GenericUtils.isEmpty(fileAttributes)
                ? IoUtils.EMPTY_FILE_ATTRIBUTES
                : fileAttributes.toArray(new FileAttribute<?>[fileAttributes.size()]);

        SftpFileSystemAccessor accessor = subsystem.getFileSystemAccessor();
        SeekableByteChannel channel;
        try {
            channel = accessor.openFile(
                    subsystem, this, file, handle, openOptions, fileAttrs);
        } catch (UnsupportedOperationException e) {
            channel = accessor.openFile(
                    subsystem, this, file, handle, openOptions, IoUtils.EMPTY_FILE_ATTRIBUTES);
            subsystem.doSetAttributes(SftpConstants.SSH_FXP_OPEN, "", file, attrs, false);
        }
        this.fileChannel = channel;

        try {
            signalHandleOpen();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public final Set<StandardOpenOption> getOpenOptions() {
        return openOptions;
    }

    public final Collection<FileAttribute<?>> getFileAttributes() {
        return fileAttributes;
    }

    public SeekableByteChannel getFileChannel() {
        return fileChannel;
    }

    public int getAccessMask() {
        return access;
    }

    public boolean isOpenAppend() {
        return (getAccessMask() & SftpConstants.ACE4_APPEND_DATA) != 0;
    }

    public int read(byte[] data, long offset) throws IOException {
        return read(data, 0, data.length, offset, null);
    }

    public int read(byte[] data, int doff, int length, long offset) throws IOException {
        return read(data, doff, length, offset, null);
    }

    @SuppressWarnings("resource")
    public int read(byte[] data, int doff, int length, long offset, AtomicReference<Boolean> eof) throws IOException {
        SeekableByteChannel channel = getFileChannel();
        channel = channel.position(offset);
        int l = channel.read(ByteBuffer.wrap(data, doff, length));
        if (l > 0 && eof != null && l < length) {
            eof.set(channel.position() >= channel.size());
        }
        return l;
    }

    public void append(byte[] data) throws IOException {
        append(data, 0, data.length);
    }

    public void append(byte[] data, int doff, int length) throws IOException {
        SeekableByteChannel channel = getFileChannel();
        write(data, doff, length, channel.size());
    }

    public void write(byte[] data, long offset) throws IOException {
        write(data, 0, data.length, offset);
    }

    public void write(byte[] data, int doff, int length, long offset) throws IOException {
        SeekableByteChannel channel = getFileChannel();
        channel = channel.position(offset);
        channel.write(ByteBuffer.wrap(data, doff, length));
    }

    @Override
    public void close() throws IOException {
        super.close();

        SftpSubsystem subsystem = getSubsystem();
        SftpFileSystemAccessor accessor = subsystem.getFileSystemAccessor();
        accessor.closeFile(subsystem, this, getFile(), getFileHandle(), getFileChannel(), getOpenOptions());
    }

    public void lock(long offset, long length, int mask) throws IOException {
        // We map delete locks to write locks, and we ignore the advisory bit.
        boolean writeLock = (mask & (SftpConstants.SSH_FXF_WRITE_LOCK | SftpConstants.SSH_FXF_DELETE_LOCK)) != 0;
        if (!writeLock) {
            // If read and write are requested, it's a write lock.
            boolean readLock = (mask & SftpConstants.SSH_FXF_READ_LOCK) != 0;
            // Draft RFC is silent on what to do if no flags at all are set:
            // https://www.ietf.org/archive/id/draft-ietf-secsh-filexfer-13.txt
            // If the handle was opened for reading, use a read lock, otherwise a write lock.
            if (!readLock && canWrite()) {
                writeLock = true;
            }
        }
        if (writeLock && !canWrite()) {
            throw new SftpException(SftpConstants.SSH_FX_BYTE_RANGE_LOCK_REFUSED,
                    "Write lock requested, but handle opened for reading only");
        } else if (!writeLock && !canRead()) {
            throw new SftpException(SftpConstants.SSH_FX_BYTE_RANGE_LOCK_REFUSED,
                    "Read lock requested, but handle opened for writing only");
        }
        SeekableByteChannel channel = getFileChannel();
        long size = (length == 0L) ? channel.size() - offset : length;
        SftpSubsystem subsystem = getSubsystem();
        SftpFileSystemAccessor accessor = subsystem.getFileSystemAccessor();
        FileLock lock = null;
        try {
            lock = accessor.tryLock(subsystem, this, getFile(), getFileHandle(), channel, offset, size, !writeLock);
        } catch (NonReadableChannelException | NonWritableChannelException e) {
            SftpException error = new SftpException(SftpConstants.SSH_FX_BYTE_RANGE_LOCK_REFUSED,
                    "Could not acquire channel lock; write=" + writeLock + ": " + e.toString());
            error.initCause(e);
            throw error;
        }

        if (lock == null) {
            throw new SftpException(SftpConstants.SSH_FX_BYTE_RANGE_LOCK_CONFLICT,
                    "Overlapping lock held by another program on range [" + offset + "-" + (offset + length) + "]");
        }
        synchronized (locks) {
            locks.add(lock);
        }
    }

    private boolean canRead() {
        return getOpenOptions().contains(StandardOpenOption.READ);
    }

    private boolean canWrite() {
        Set<StandardOpenOption> options = getOpenOptions();
        return options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND);
    }

    public void unlock(long offset, long length) throws IOException {
        SeekableByteChannel channel = getFileChannel();
        long size = (length == 0L) ? channel.size() - offset : length;
        FileLock lock = null;
        for (Iterator<FileLock> iterator = locks.iterator(); iterator.hasNext();) {
            FileLock l = iterator.next();
            if ((l.position() == offset) && (l.size() == size)) {
                iterator.remove();
                lock = l;
                break;
            }
        }
        if (lock == null) {
            throw new SftpException(SftpConstants.SSH_FX_NO_MATCHING_BYTE_RANGE_LOCK,
                    "No matching lock found on range [" + offset + "-" + (offset + length));
        }

        lock.release();
    }

    public static Collection<FileAttribute<?>> toFileAttributes(Map<String, ?> attrs) {
        if (MapEntryUtils.isEmpty(attrs)) {
            return Collections.emptyList();
        }

        Collection<FileAttribute<?>> attributes = null;
        // Cannot use forEach because the referenced attributes variable is not effectively final
        for (Map.Entry<String, ?> attr : attrs.entrySet()) {
            FileAttribute<?> fileAttr = toFileAttribute(attr.getKey(), attr.getValue());
            if (fileAttr == null) {
                continue;
            }
            if (attributes == null) {
                attributes = new LinkedList<>();
            }
            attributes.add(fileAttr);
        }

        return (attributes == null) ? Collections.emptyList() : attributes;
    }

    public static FileAttribute<?> toFileAttribute(String key, Object val) {
        // Some ignored attributes sent by the SFTP client
        if (IoUtils.OTHERFILE_VIEW_ATTR.equals(key)) {
            if ((Boolean) val) {
                throw new IllegalArgumentException("Not allowed to use " + key + "=" + val);
            }
            return null;
        } else if (IoUtils.REGFILE_VIEW_ATTR.equals(key)) {
            if (!(Boolean) val) {
                throw new IllegalArgumentException("Not allowed to use " + key + "=" + val);
            }
            return null;
        }

        return new FileAttribute<Object>() {
            private final String s = key + "=" + val;

            @Override
            public String name() {
                return key;
            }

            @Override
            public Object value() {
                return val;
            }

            @Override
            public String toString() {
                return s;
            }
        };
    }

    public static Set<StandardOpenOption> getOpenOptions(int flags, int access) {
        Set<StandardOpenOption> options = EnumSet.noneOf(StandardOpenOption.class);
        if ((access & (SftpConstants.ACE4_READ_DATA | SftpConstants.ACE4_READ_ATTRIBUTES)) != 0) {
            options.add(StandardOpenOption.READ);
        }
        if ((access & (SftpConstants.ACE4_WRITE_DATA | SftpConstants.ACE4_WRITE_ATTRIBUTES)) != 0) {
            options.add(StandardOpenOption.WRITE);
        }

        int accessDisposition = flags & SftpConstants.SSH_FXF_ACCESS_DISPOSITION;
        switch (accessDisposition) {
            case SftpConstants.SSH_FXF_CREATE_NEW:
                options.add(StandardOpenOption.CREATE_NEW);
                break;
            case SftpConstants.SSH_FXF_CREATE_TRUNCATE:
                options.add(StandardOpenOption.CREATE);
                options.add(StandardOpenOption.TRUNCATE_EXISTING);
                break;
            case SftpConstants.SSH_FXF_OPEN_EXISTING:
                break;
            case SftpConstants.SSH_FXF_OPEN_OR_CREATE:
                options.add(StandardOpenOption.CREATE);
                break;
            case SftpConstants.SSH_FXF_TRUNCATE_EXISTING:
                options.add(StandardOpenOption.TRUNCATE_EXISTING);
                break;
            default: // ignored
        }
        if ((flags & (SftpConstants.SSH_FXF_APPEND_DATA | SftpConstants.SSH_FXF_APPEND_DATA_ATOMIC)) != 0) {
            options.add(StandardOpenOption.APPEND);
        }

        return options;
    }
}
