/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */
package org.aion.p2p.impl1.tasks;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.p2p.INode;
import org.aion.p2p.INodeMgr;
import org.aion.p2p.IP2pMgr;
import org.aion.p2p.impl.zero.msg.ReqHandshake1;
import org.aion.p2p.impl1.P2pMgr.Dest;

public class TaskConnectPeers implements Runnable {

    private static final int PERIOD_CONNECT_OUTBOUND = 1000;
    private static final int TIMEOUT_OUTBOUND_CONNECT = 10000;

    private final INodeMgr nodeMgr;
    private final int maxActiveNodes;
    private final IP2pMgr mgr;
    private final AtomicBoolean start;
    private final BlockingQueue<MsgOut> sendMsgQue;
    private final Selector selector;
    private final ReqHandshake1 cachedReqHS;

    public TaskConnectPeers(
        final IP2pMgr _mgr,
        final AtomicBoolean _start,
        final INodeMgr _nodeMgr,
        final int _maxActiveNodes,
        final Selector _selector,
        final BlockingQueue<MsgOut> _sendMsgQue,
        final ReqHandshake1 _cachedReqHS) {

        this.start = _start;
        this.nodeMgr = _nodeMgr;
        this.maxActiveNodes = _maxActiveNodes;
        this.mgr = _mgr;
        this.selector = _selector;
        this.sendMsgQue = _sendMsgQue;
        this.cachedReqHS = _cachedReqHS;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("p2p-tcp");
        while (this.start.get()) {
            try {
                Thread.sleep(PERIOD_CONNECT_OUTBOUND);
            } catch (InterruptedException e) {
                mgr.getLogger().warn("tcp-interrupted");
            }

            if (this.nodeMgr.activeNodesSize() >= this.maxActiveNodes) {
                mgr.getLogger().warn("tcp-connect-peer pass max-active-nodes");
                continue;
            }

            INode node;
            try {
                node = this.nodeMgr.tempNodesTake();
                if (this.nodeMgr.isSeedIp(node.getIpStr())) {
                    node.setFromBootList(true);
                }
                if (node.getIfFromBootList()) {
                    this.nodeMgr.addTempNode(node);
                }
                // if (node.peerMetric.shouldNotConn()) {
                // continue;
                // }
            } catch (InterruptedException e) {
                mgr.getLogger().error("tcp-interrupted");
                return;
            } catch (Exception e) {
                e.printStackTrace();
                mgr.getLogger().warn("tcp-Exception {}", e.getMessage());
                continue;
            }
            int nodeIdHash = node.getIdHash();
            if (!this.nodeMgr.getOutboundNodes().containsKey(nodeIdHash)
                && this.nodeMgr.notActiveNode(nodeIdHash)) {
                int _port = node.getPort();
                try {
                    SocketChannel channel = SocketChannel.open();

                    channel.socket()
                        .connect(
                            new InetSocketAddress(node.getIpStr(), _port),
                            TIMEOUT_OUTBOUND_CONNECT);
                    this.mgr.configChannel(channel);

                    if (channel.isConnected()) {

                        if (mgr.getLogger().isDebugEnabled()) {
                            mgr.getLogger().debug("success-connect node-id={} ip=", node.getIdShort(),
                                node.getIpStr());
                        }

                        SelectionKey sk = channel.register(this.selector, SelectionKey.OP_READ);
                        ChannelBuffer rb = new ChannelBuffer(mgr);
                        rb.setDisplayId(node.getIdShort());
                        rb.setNodeIdHash(nodeIdHash);
                        sk.attach(rb);

                        node.refreshTimestamp();
                        node.setChannel(channel);
                        this.nodeMgr.addOutboundNode(node);

                        if (mgr.getLogger().isDebugEnabled()) {
                            mgr.getLogger().debug("prepare-request-handshake -> id={} ip={}",
                                node.getIdShort(), node.getIpStr());
                        }

                        this.sendMsgQue.offer(
                            new MsgOut(
                                node.getIdHash(),
                                node.getIdShort(),
                                this.cachedReqHS,
                                Dest.OUTBOUND));
                        // node.peerMetric.decFailedCount();

                    } else {
                        if (mgr.getLogger().isDebugEnabled()) {
                            mgr.getLogger()
                                .debug("fail-connect node-id -> id={} ip={}", node.getIdShort(),
                                    node.getIpStr());
                        }

                        channel.close();
                        // node.peerMetric.incFailedCount();
                    }
                } catch (IOException e) {
                    if (mgr.getLogger().isDebugEnabled()) {
                        mgr.getLogger().debug("connect-outbound io-exception addr={}:{} result={}",
                            node.getIpStr(), _port, e.getMessage());
                    }
                    // node.peerMetric.incFailedCount();
                } catch (Exception e) {
                    e.printStackTrace();
                    mgr.getLogger()
                        .debug("connect-outbound exception -> id={} ip={}", node.getIdShort(),
                            node.getIpStr());
                }
            }
        }
    }
}
