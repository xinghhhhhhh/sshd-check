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
package org.apache.sshd.client.channel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.LocalWindow;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ChannelDirectTcpip extends AsyncCapableClientChannel {

    private final SshdSocketAddress local;
    private final SshdSocketAddress remote;

    public ChannelDirectTcpip(SshdSocketAddress local, SshdSocketAddress remote) {
        super("direct-tcpip", false);
        if (local == null) {
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                local = new SshdSocketAddress(localHost.getHostName(), 0);
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Unable to retrieve local host name");
            }
        }
        if (remote == null) {
            throw new IllegalArgumentException("Remote address must not be null");
        }
        this.local = local;
        this.remote = remote;
    }

    @Override
    public synchronized OpenFuture open() throws IOException {
        if (closeFuture.isClosed()) {
            throw new SshException("Session has been closed");
        }

        openFuture = new DefaultOpenFuture(remote, futureLock);
        if (log.isDebugEnabled()) {
            log.debug("open({}) SSH_MSG_CHANNEL_OPEN", this);
        }

        Session session = getSession();
        String remoteName = remote.getHostName();
        String localName = local.getHostName();
        LocalWindow wLocal = getLocalWindow();
        String type = getChannelType();
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN,
                type.length() + remoteName.length() + localName.length() + Long.SIZE);
        buffer.putString(type);
        buffer.putUInt(getChannelId());
        buffer.putUInt(wLocal.getSize());
        buffer.putUInt(wLocal.getPacketSize());
        buffer.putString(remoteName);
        buffer.putUInt(remote.getPort());
        buffer.putString(localName);
        buffer.putUInt(local.getPort());
        writePacket(buffer);
        return openFuture;
    }

    public SshdSocketAddress getLocalSocketAddress() {
        return this.local;
    }

    public SshdSocketAddress getRemoteSocketAddress() {
        return this.remote;
    }
}
