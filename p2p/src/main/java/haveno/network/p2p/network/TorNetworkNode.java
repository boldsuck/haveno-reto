/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import haveno.common.util.Hex;
import haveno.network.p2p.NodeAddress;

import haveno.common.Timer;
import haveno.common.UserThread;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.util.SingleThreadExecutorUtils;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.Socket;
import java.net.InetAddress;
import java.net.ServerSocket;

import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNode extends NetworkNode {
    private static final long SHUT_DOWN_TIMEOUT = 2;

    private final String torControlHost;
    private final String serviceAddress;

    private Timer shutDownTimeoutTimer;
    private TorMode torMode;
    private boolean streamIsolation;
    private boolean shutDownInProgress;
    private boolean shutDownComplete;
    private final ExecutorService executor;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TorNetworkNode(String hiddenServiceAddress, int servicePort,
            NetworkProtoResolver networkProtoResolver,
            boolean useStreamIsolation,
            TorMode torMode,
            @Nullable BanFilter banFilter,
            int maxConnections, String torControlHost) {
        super(servicePort, networkProtoResolver, banFilter, maxConnections);
        this.serviceAddress = hiddenServiceAddress;
        this.torMode = torMode;
        this.streamIsolation = useStreamIsolation;
        this.torControlHost = torControlHost;

        executor = SingleThreadExecutorUtils.getSingleThreadExecutor("StartTor");
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void start(@Nullable SetupListener setupListener) {
        torMode.doRollingBackup();

        if (setupListener != null)
            addSetupListener(setupListener);

        createTorAndHiddenService(servicePort);
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        // https://www.ietf.org/rfc1928.txt SOCKS5 Protocol
        try {
            checkArgument(peerNodeAddress.getHostName().endsWith(".onion"), "PeerAddress is not an onion address");
            Socket sock = new Socket(InetAddress.getLoopbackAddress(), 9050);
            sock.getOutputStream().write(Hex.decode("050100"));
            String response = Hex.encode(sock.getInputStream().readNBytes(2));
            if (!response.equalsIgnoreCase("0500")) {
                return null;
            }
            String connect_details = "050100033E" + Hex.encode(peerNodeAddress.getHostName().getBytes(StandardCharsets.UTF_8));
            StringBuilder connect_port = new StringBuilder(Integer.toHexString(peerNodeAddress.getPort()));
            while (connect_port.length() < 4) connect_port.insert(0, "0");
            connect_details = connect_details + connect_port;
            sock.getOutputStream().write(Hex.decode(connect_details));
            response = Hex.encode(sock.getInputStream().readNBytes(10));
            if (response.substring(0, 2).equalsIgnoreCase("05") && response.substring(2, 4).equalsIgnoreCase("00")) {
                return sock;    // success
            }
            if (response.substring(2, 4).equalsIgnoreCase("04")) {
                log.warn("Host unreachable: {}", peerNodeAddress);
            } else {
                log.warn("SOCKS error code received {} expected 00", response.substring(2, 4));
            }
            if (!response.substring(0, 2).equalsIgnoreCase("05")) {
                log.warn("unexpected response, this isn't a SOCKS5 proxy?: {} {}", response, response.substring(0, 2));
            }
        } catch (Exception e) {
            log.warn(e.toString());
        }
        throw new IOException("createSocket failed");
    }

    public Socks5Proxy getSocksProxy() {
        try {
            Socks5Proxy prox = new Socks5Proxy(InetAddress.getLoopbackAddress(), 9050);
            prox.resolveAddrLocally(false);
            return prox;
        } catch (Exception e) {
            log.warn(e.toString());
            return null;
        }
    }

    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        log.info("TorNetworkNode shutdown started");
        if (shutDownComplete) {
            log.info("TorNetworkNode shutdown already completed");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            return;
        }
        if (shutDownInProgress) {
            log.warn("Ignoring request to shut down because shut down is in progress");
            return;
        }
        shutDownInProgress = true;

        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            shutDownComplete = true;
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            executor.shutdownNow();
        }, SHUT_DOWN_TIMEOUT);

        super.shutDown(() -> {
            try {
                executor.shutdownNow();
            } catch (Throwable e) {
                log.error("Shutdown torNetworkNode failed with exception", e);
            } finally {
                shutDownTimeoutTimer.stop();
                shutDownComplete = true;
                if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Create tor and hidden service
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTorAndHiddenService(int servicePort) {
        executor.submit(() -> {
            try {
                // listener for incoming messages at the hidden service
                ServerSocket socket = new ServerSocket(servicePort);
                nodeAddressProperty.set(new NodeAddress(serviceAddress + ":" + servicePort));
                log.info("\n################################################################\n" +
                                "Tor hidden service published: {} Port: {}\n" +
                                "################################################################",
                        serviceAddress, servicePort);
                UserThread.execute(() -> setupListeners.forEach(SetupListener::onTorNodeReady));
                UserThread.runAfter(() -> {
                    nodeAddressProperty.set(new NodeAddress(serviceAddress + ":" + servicePort));
                    startServer(socket);
                    setupListeners.forEach(SetupListener::onHiddenServicePublished);
                }, 3);
                return null;
            } catch (IOException e) {
                log.error("Could not connect to running Tor", e);
                UserThread.execute(() -> setupListeners.forEach(s -> s.onSetupFailed(new RuntimeException(e.getMessage()))));
            } catch (Throwable ignore) {
            }
            return null;
        });
    }
}
