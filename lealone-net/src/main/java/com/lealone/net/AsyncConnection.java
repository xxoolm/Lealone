/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.net;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.api.ErrorCode;

/**
 * An async connection.
 */
public abstract class AsyncConnection {

    protected final WritableChannel writableChannel;
    protected final boolean isServer;
    protected InetSocketAddress inetSocketAddress;
    protected boolean closed;

    public AsyncConnection(WritableChannel writableChannel, boolean isServer) {
        this.writableChannel = writableChannel;
        this.isServer = isServer;
    }

    public abstract void handle(NetBuffer buffer, boolean autoRecycle);

    // 包长度占用了多少个字节
    public int getPacketLengthByteCount() {
        return 4;
    }

    public int getPacketLength(ByteBuffer buffer) {
        return buffer.getInt();
    }

    public WritableChannel getWritableChannel() {
        return writableChannel;
    }

    public String getHostAndPort() {
        return writableChannel.getHost() + ":" + writableChannel.getPort();
    }

    public InetSocketAddress getInetSocketAddress() {
        return inetSocketAddress;
    }

    public void setInetSocketAddress(InetSocketAddress inetSocketAddress) {
        this.inetSocketAddress = inetSocketAddress;
    }

    public void close() {
        closed = true;
        if (writableChannel != null) {
            writableChannel.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void checkClosed() {
        if (closed) {
            String msg = "Connection[" + inetSocketAddress.getHostName() + "] is closed";
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, msg);
        }
    }

    public void handleException(Exception e) {
    }

    public void checkTimeout(long currentTime) {
    }

    public boolean isShared() {
        return false;
    }

    public int getSharedSize() {
        return 0;
    }

    public int getMaxSharedSize() {
        return 0;
    }

    public boolean isServer() {
        return isServer;
    }
}
