package threads.lite.host;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import net.luminis.quic.ConnectionIssue;
import net.luminis.quic.QuicClientConnection;
import net.luminis.quic.QuicClientConnectionImpl;
import net.luminis.quic.QuicConnection;
import net.luminis.quic.TransportParameters;
import net.luminis.quic.Version;
import net.luminis.quic.log.SysOutLogger;
import net.luminis.quic.server.ApplicationProtocolConnection;
import net.luminis.quic.server.ApplicationProtocolConnectionFactory;
import net.luminis.quic.server.Server;
import net.luminis.quic.stream.QuicStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import bitswap.pb.MessageOuterClass;
import identify.pb.IdentifyOuterClass;
import threads.lite.IPFS;
import threads.lite.LogUtils;
import threads.lite.bitswap.BitSwap;
import threads.lite.bitswap.BitSwapMessage;
import threads.lite.cid.Cid;
import threads.lite.cid.Multiaddr;
import threads.lite.cid.PeerId;
import threads.lite.cid.Protocol;
import threads.lite.core.Closeable;
import threads.lite.core.ClosedException;
import threads.lite.core.TimeoutCloseable;
import threads.lite.crypto.PrivKey;
import threads.lite.crypto.PubKey;
import threads.lite.dht.KadDht;
import threads.lite.dht.Routing;
import threads.lite.format.BlockStore;
import threads.lite.ident.IdentityService;
import threads.lite.ipns.Ipns;
import threads.lite.push.Push;
import threads.lite.relay.RelayService;


public class LiteHost {
    @NonNull
    public static final ConcurrentHashMap<PeerId, PubKey> remotes = new ConcurrentHashMap<>();
    @NonNull
    private static final ExecutorService executors = Executors.newFixedThreadPool(2);
    @NonNull
    private static final String TAG = LiteHost.class.getSimpleName();
    @NonNull
    private static final Duration DefaultRecordEOL = Duration.ofHours(24);
    @NonNull
    private static final TrustManager tm = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String s) {
            try {
                if (IPFS.EVALUATE_PEER) {
                    for (X509Certificate cert : chain) {
                        PubKey pubKey = LiteHostCertificate.extractPublicKey(cert);
                        Objects.requireNonNull(pubKey);
                        PeerId peerId = PeerId.fromPubKey(pubKey);
                        Objects.requireNonNull(peerId);
                        remotes.put(peerId, pubKey);
                    }
                }
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String s) {

            try {
                if (IPFS.EVALUATE_PEER) {
                    for (X509Certificate cert : chain) {
                        PubKey pubKey = LiteHostCertificate.extractPublicKey(cert);
                        Objects.requireNonNull(pubKey);
                        PeerId peerId = PeerId.fromPubKey(pubKey);
                        Objects.requireNonNull(peerId);
                        remotes.put(peerId, pubKey);
                    }
                }
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
    private static int failure = 0;
    private static int success = 0;
    @NonNull
    public final List<ConnectionHandler> handlers = new ArrayList<>();
    @NonNull
    private final ConcurrentSkipListSet<PeerId> relays = new ConcurrentSkipListSet<>();
    @NonNull
    private final ConcurrentSkipListSet<InetAddress> addresses = new ConcurrentSkipListSet<>(
            Comparator.comparing(InetAddress::getHostAddress)
    );
    @NonNull
    private final ConcurrentHashMap<PeerId, Set<Multiaddr>> addressBook = new ConcurrentHashMap<>();
    @NonNull
    private final Routing routing;
    @NonNull
    private final PrivKey privKey;
    @NonNull
    private final BitSwap bitSwap;

    private final int port;
    @NonNull
    private final LiteHostCertificate selfSignedCertificate;
    @NonNull
    private final Set<PeerId> swarm = ConcurrentHashMap.newKeySet();
    @NonNull
    public final AtomicBoolean inet6 = new AtomicBoolean(false);
    @Nullable
    private Push push;
    private Server server;

    public LiteHost(@NonNull LiteHostCertificate selfSignedCertificate,
                    @NonNull PrivKey privKey,
                    @NonNull BlockStore blockstore,
                    int alpha) {
        this.selfSignedCertificate = selfSignedCertificate;
        this.privKey = privKey;


        this.routing = new KadDht(this,
                new Ipns(), alpha, IPFS.DHT_BUCKET_SIZE);

        this.bitSwap = new BitSwap(blockstore, this);
        int port = IPFS.DEFAULT_PORT;
        if (!isLocalPortFree(port)) {
            port = nextFreePort();
        }
        this.port = port;
        try {
            List<Version> supportedVersions = new ArrayList<>();
            supportedVersions.add(Version.IETF_draft_29);
            supportedVersions.add(Version.QUIC_version_1);

            boolean requireRetry = false; // TODO what does it mean
            server = new Server(port, IPFS.APRN,
                    new FileInputStream(selfSignedCertificate.certificate()),
                    new FileInputStream(selfSignedCertificate.privateKey()),
                    supportedVersions, requireRetry,
                    new ApplicationProtocolConnectionFactory() {
                        @Override
                        public ApplicationProtocolConnection createConnection(String protocol,
                                                                              QuicConnection quicConnection) throws Exception {

                            return new ServerHandler(LiteHost.this, quicConnection);

                        }
                    });
            server.start();
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

    }

    private static int nextFreePort() {
        int port = ThreadLocalRandom.current().nextInt(4001, 65535);
        while (true) {
            if (isLocalPortFree(port)) {
                return port;
            } else {
                port = ThreadLocalRandom.current().nextInt(4001, 65535);
            }
        }
    }

    private static boolean isLocalPortFree(int port) {
        try {
            new ServerSocket(port).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void relays() {
        Set<String> addresses = new HashSet<>(IPFS.IPFS_RELAYS_NODES);


        for (String address : addresses) {
            try {
                Multiaddr multiaddr = new Multiaddr(address);
                String name = multiaddr.getStringComponent(Protocol.Type.P2P);
                Objects.requireNonNull(name);
                PeerId peerId = PeerId.fromBase58(name);
                Objects.requireNonNull(peerId);
                addToAddressBook(peerId, Collections.singletonList(multiaddr), false);
                relays.add(peerId);
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
        }
    }

    public long getMaxTime() {
        return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
    }

    @NonNull
    public Routing getRouting() {
        return routing;
    }

    @NonNull
    public BitSwap getBitSwap() {
        return bitSwap;
    }

    public boolean gatePeer(@NonNull PeerId peerID) {
        return !swarmContains(peerID);
    }

    public void receiveMessage(@NonNull PeerId peer, @NonNull BitSwapMessage incoming) {
        bitSwap.receiveMessage(peer, incoming);
    }

    public PeerId self() {
        return PeerId.fromPubKey(privKey.publicKey());
    }

    public void addConnectionHandler(@NonNull ConnectionHandler connectionHandler) {
        handlers.add(connectionHandler);
    }

    public void forwardMessage(@NonNull PeerId peerId, @NonNull MessageLite msg) {
        if (msg instanceof MessageOuterClass.Message) {
            try {
                BitSwapMessage message = BitSwapMessage.newMessageFromProto(
                        (MessageOuterClass.Message) msg);
                receiveMessage(peerId, message);
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable.getMessage());
            }
        }
    }

    public void findProviders(@NonNull Closeable closeable, @NonNull Routing.Providers providers,
                              @NonNull Cid cid) {
        routing.findProviders(closeable, providers, cid);
    }

    public boolean hasAddresses(@NonNull PeerId peerId) {
        Collection<Multiaddr> addrInfo = addressBook.get(peerId);
        if (addrInfo != null) {
            return !addrInfo.isEmpty();
        }
        return false;
    }

    @NonNull
    public Set<Multiaddr> getAddresses(@NonNull PeerId peerId) {
        try {
            Collection<Multiaddr> addrInfo = addressBook.get(peerId);
            if (addrInfo != null) {
                return new HashSet<>(addrInfo);
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return Collections.emptySet();
    }

    @NonNull
    private List<Multiaddr> prepareAddresses(@NonNull PeerId peerId) {
        List<Multiaddr> all = new ArrayList<>();
        for (Multiaddr ma : getAddresses(peerId)) {
            try {
                if (ma.has(Protocol.Type.DNS)) {
                    all.add(DnsResolver.resolveDns(ma));
                } else if (ma.has(Protocol.Type.DNS6)) {
                    all.add(DnsResolver.resolveDns6(ma));
                } else if (ma.has(Protocol.Type.DNS4)) {
                    all.add(DnsResolver.resolveDns4(ma));
                } else if (ma.has(Protocol.Type.DNSADDR)) {
                    all.addAll(DnsResolver.resolveDnsAddress(ma));
                } else {
                    all.add(ma);
                }
            } catch (Throwable throwable) {
                LogUtils.error(TAG, ma.toString() + " prepareAddresses " + throwable);
            }
        }
        List<Multiaddr> result = new ArrayList<>();
        for (Multiaddr ma : all) {
            if (isSupported(ma, true)) {
                result.add(ma);
            }
        }
        return result;
    }

    public boolean findPeer(@NonNull Closeable closeable, @NonNull PeerId peerId) {
        AtomicBoolean done = new AtomicBoolean(false);
        routing.findPeer(() -> closeable.isClosed() || done.get(), peerId1 -> done.set(true), peerId);
        return done.get();
    }



    public void publishName(@NonNull Closeable closable, @NonNull PrivKey privKey,
                            @NonNull String name, @NonNull PeerId id, int sequence) {


        Date eol = Date.from(new Date().toInstant().plus(DefaultRecordEOL));

        Duration duration = Duration.ofHours(IPFS.IPNS_DURATION);
        ipns.pb.Ipns.IpnsEntry
                record = Ipns.create(privKey, name.getBytes(), sequence, eol, duration);

        PubKey pk = privKey.publicKey();

        record = Ipns.embedPublicKey(pk, record);

        byte[] bytes = record.toByteArray();

        byte[] ipns = IPFS.IPNS_PATH.getBytes();
        byte[] ipnsKey = Bytes.concat(ipns, id.getBytes());
        routing.putValue(closable, ipnsKey, bytes);
    }

    @NonNull
    public PeerInfo getPeerInfo(@NonNull Closeable closeable, @NonNull PeerId peerId)
            throws Exception {


        QuicClientConnection conn = connect(closeable, peerId, IPFS.CONNECT_TIMEOUT,
                IPFS.GRACE_PERIOD, IPFS.MIN_STREAMS, IPFS.IDENTITY_STREAM_SIZE_LIMIT);

        return IdentityService.getPeerInfo(peerId, conn);
    }

    public void swarmReduce(@NonNull PeerId peerId) {
        swarm.remove(peerId);
    }

    private boolean isSupported(@NonNull Multiaddr address, boolean acceptLocal) {

        if (address.has(Protocol.Type.DNSADDR)) {
            return true;
        }
        if (address.has(Protocol.Type.DNS)) {
            return true;
        }
        if (address.has(Protocol.Type.DNS4)) {
            return true;
        }
        if (address.has(Protocol.Type.DNS6)) {
            return true;
        }

        try {
            InetAddress inetAddress = InetAddress.getByName(address.getHost());
            if (inetAddress.isAnyLocalAddress() || inetAddress.isLinkLocalAddress()
                    || (!acceptLocal && inetAddress.isLoopbackAddress())
                    || (!acceptLocal && inetAddress.isSiteLocalAddress())) {
                return false;
            }
        } catch (Throwable throwable) {
            LogUtils.debug(TAG, "" + throwable);
            return false;
        }

        return address.has(Protocol.Type.QUIC);
    }

    public boolean addToAddressBook(@NonNull PeerId peerId,
                                    @NonNull Collection<Multiaddr> addresses,
                                    boolean acceptSiteLocal) {
        boolean added = false;
        try {
            synchronized (peerId.toBase58().intern()) {
                Set<Multiaddr> info = addressBook.get(peerId);
                if (info == null) {
                    info = new HashSet<>();
                    addressBook.put(peerId, info);
                }
                for (Multiaddr ma : addresses) {
                    if (isSupported(ma, acceptSiteLocal)) {
                        info.add(ma);
                        added = true;
                    }
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return added;
    }

    public void handleConnection(@NonNull PeerId peerId, @NonNull QuicConnection connection,
                                 boolean incoming) {

        if (handlers.size() > 0) {
            executors.execute(() -> {
                for (ConnectionHandler handle : handlers) {
                    try {
                        if (incoming) {
                            handle.incomingConnection(peerId, connection);
                        } else {
                            handle.outgoingConnection(peerId, connection);
                        }
                    } catch (Throwable throwable) {
                        LogUtils.error(TAG, throwable);
                    }
                }
            });
        }
    }

    public void swarmEnhance(@NonNull PeerId peerId) {
        swarm.add(peerId);
    }

    @NonNull
    public Set<PeerId> getPeers() {
        return new HashSet<>(swarm);
    }

    @Nullable
    public QuicStream getRelayStream(
            @NonNull Closeable closeable, @NonNull PeerId peerId) {

        for (PeerId relay : relays) {
            try {
                return getStream(closeable, relay, peerId);
            } catch (Throwable throwable) {
                LogUtils.error(TAG, throwable);
            }
        }
        return null;
    }

    @NonNull
    private QuicStream getStream(@NonNull Closeable closeable, @NonNull PeerId relay,
                                 @NonNull PeerId peerId)
            throws ConnectionIssue, ClosedException {

        try {
            QuicClientConnection conn = connect(closeable, relay, IPFS.CONNECT_TIMEOUT,
                    IPFS.RELAY_GRACE_PERIOD, IPFS.MIN_STREAMS, IPFS.MESSAGE_SIZE_MAX);

            return RelayService.getStream(conn, self(), peerId, IPFS.RELAY_GRACE_PERIOD);

        } catch (ClosedException | ConnectionIssue exception) {
            throw exception;
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
            throw new ConnectionIssue();
        }
    }

    public boolean canHop(@NonNull Closeable closeable, @NonNull PeerId peerId)
            throws ConnectionIssue, ClosedException {

        try {
            QuicClientConnection conn = connect(closeable, peerId, IPFS.CONNECT_TIMEOUT,
                    IPFS.GRACE_PERIOD, IPFS.MIN_STREAMS, IPFS.PROTOCOL_READER_LIMIT);

            return RelayService.canHop(conn);

        } catch (ClosedException | ConnectionIssue exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    @NonNull
    public List<Multiaddr> listenAddresses() {
        try {
            // TODO the listen address does not contain real IP address
            // TODO given from other peers

            List<Multiaddr> list = new ArrayList<>();

            for (InetAddress inetAddress : addresses) {
                String pre = "/ip4/";
                if (inetAddress instanceof Inet6Address) {
                    pre = "/ip6/";
                }

                Multiaddr multiaddr = new Multiaddr(pre +
                        inetAddress.getHostAddress() + "/udp/" + port + "/quic");

                list.add(multiaddr);
            }

            return list;
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
        return Collections.emptyList();

    }

    private void evaluateDefaultHost() throws UnknownHostException {
        if (addresses.isEmpty()) {
            String host = evaluateHost();
            addresses.add(InetAddress.getByName(host));
        }
    }

    public Set<PeerId> bootstrap() {
        Set<PeerId> peers = new HashSet<>();

        try {
            Set<String> addresses = DnsResolver.resolveDnsAddress(IPFS.LIB2P_DNS);
            addresses.addAll(IPFS.DHT_BOOTSTRAP_NODES);

            for (String address : addresses) {
                try {
                    Multiaddr multiaddr = new Multiaddr(address);
                    String name = multiaddr.getStringComponent(Protocol.Type.P2P);
                    Objects.requireNonNull(name);
                    PeerId peerId = PeerId.fromBase58(name);
                    Objects.requireNonNull(peerId);

                    addToAddressBook(peerId,
                            Collections.singletonList(multiaddr), false);
                    peers.add(peerId);
                } catch (Throwable throwable) {
                    LogUtils.error(TAG, throwable);
                }
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

        return peers;
    }

    public String evaluateHost() {
        String host = "127.0.0.1";
        if (inet6.get()) {
            host = "::1";
        }

        AtomicReference<String> result = new AtomicReference<>(host);


        Set<PeerId> peers = bootstrap();
        for (PeerId peerId : peers) {
            try {
                PeerInfo peerInfo = getPeerInfo(new TimeoutCloseable(IPFS.CONNECT_TIMEOUT), peerId);
                Multiaddr observed = peerInfo.getObserved();
                if (observed != null) {
                    if (Objects.equals(result.get(), observed.getHost())) {
                        break;

                    } else {
                        result.set(observed.getHost());
                    }
                }
            } catch (Throwable ignore) {
                // ignore
            }
        }


        return result.get();
    }

    @NonNull
    public QuicClientConnection connect(@NonNull Closeable closeable,
                                        @NonNull PeerId peerId,
                                        int timeout,
                                        int maxIdleTimeoutInSeconds,
                                        int initialMaxStreams,
                                        int initialMaxStreamData)
            throws ConnectionIssue, ClosedException {


        if (closeable.isClosed()) {
            throw new ClosedException();
        }


        boolean ipv6 = inet6.get();
        List<Multiaddr> multiaddrs = prepareAddresses(peerId);

        if (multiaddrs.isEmpty()) {
            LogUtils.debug(TAG, "Run false" + " Success " + success + " " +
                    "Failure " + failure + " " + "/p2p/" + peerId.toBase58() + " " +
                    "No address");
            throw new ConnectionIssue();
        }

        for (Multiaddr address : multiaddrs) {

            if (ipv6 && !address.has(Protocol.Type.IP6)) {
                continue;
            }
            if (!ipv6 && address.has(Protocol.Type.IP6)) {
                continue;
            }

            long start = System.currentTimeMillis();
            boolean run = false;
            try {
                QuicClientConnectionImpl quicClientConnection = dial(address);
                Objects.requireNonNull(quicClientConnection);
                quicClientConnection.connect(timeout, IPFS.APRN,
                        new TransportParameters(maxIdleTimeoutInSeconds, initialMaxStreamData,
                                initialMaxStreams, 0), null);


                //quicClientConnection.setPeerInitiatedStreamCallback(quicStream ->
                //       new StreamHandler(quicClientConnection, quicStream, peerId, LiteHost.this));

                handleConnection(peerId, quicClientConnection, false);
                run = true;
                return quicClientConnection;
            } catch (TimeoutException ignore) {
                // nothing to do here
            } catch (Throwable throwable) {
                LogUtils.error(TAG, "Failure " + throwable + " " +
                        address + " " + (System.currentTimeMillis() - start));
            } finally {
                if (run) {
                    success++;
                } else {
                    failure++;
                }

                LogUtils.debug(TAG, "Run " + run + " Success " + success + " " +
                        "Failure " + failure +
                        " Peer " + peerId.toBase58() + " " +
                        address + " " + (System.currentTimeMillis() - start));
            }

        }

        throw new ConnectionIssue();

    }

    public void push(@NonNull PeerId peerId, @NonNull byte[] content) {
        try {
            Objects.requireNonNull(peerId);
            Objects.requireNonNull(content);

            if (push != null) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.submit(() -> push.push(peerId, new String(content)));
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }

    }

    public void setPush(@Nullable Push push) {
        this.push = push;
    }

    @NonNull
    public Multiaddr transform(@NonNull InetSocketAddress inetSocketAddress) {

        InetAddress inetAddress = inetSocketAddress.getAddress();
        boolean ipv6 = false;
        if (inetAddress instanceof Inet6Address) {
            ipv6 = true;
        }
        int port = inetSocketAddress.getPort();
        String multiaddress = "";
        if (ipv6) {
            multiaddress = multiaddress.concat("/ip6/");
        } else {
            multiaddress = multiaddress.concat("/ip4/");
        }
        multiaddress = multiaddress + inetAddress.getHostAddress() + "/udp/" + port + "/quic";
        return new Multiaddr(multiaddress);

    }

    public IdentifyOuterClass.Identify createIdentity(@Nullable InetSocketAddress inetSocketAddress) {


        IdentifyOuterClass.Identify.Builder builder = IdentifyOuterClass.Identify.newBuilder()
                .setAgentVersion(IPFS.AGENT)
                .setPublicKey(ByteString.copyFrom(privKey.publicKey().bytes()))
                .setProtocolVersion(IPFS.PROTOCOL_VERSION);

        List<Multiaddr> addresses = listenAddresses();
        for (Multiaddr addr : addresses) {
            builder.addListenAddrs(ByteString.copyFrom(addr.getBytes()));
        }

        List<String> protocols = getProtocols();
        for (String protocol : protocols) {
            builder.addProtocols(protocol);
        }

        if (inetSocketAddress != null) {
            Multiaddr observed = transform(inetSocketAddress);
            builder.setObservedAddr(ByteString.copyFrom(observed.getBytes()));
        }

        return builder.build();
    }

    private List<String> getProtocols() {
        return Arrays.asList(IPFS.STREAM_PROTOCOL, IPFS.IDENTITY_PROTOCOL, IPFS.BITSWAP_PROTOCOL);
    }

    public void shutdown() {
        try {
            if (server != null) {
                server.shutdown();
            }
        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        } finally {
            server = null;
        }
    }

    public QuicClientConnectionImpl dial(@NonNull Multiaddr multiaddr) throws IOException {

        InetAddress inetAddress;
        if (multiaddr.has(Protocol.Type.IP4)) {
            inetAddress = Inet4Address.getByName(multiaddr.getStringComponent(Protocol.Type.IP4));
        } else if (multiaddr.has(Protocol.Type.IP6)) {
            inetAddress = Inet6Address.getByName(multiaddr.getStringComponent(Protocol.Type.IP6));
        } else {
            throw new RuntimeException();
        }
        int port = multiaddr.getPort();
        String host = inetAddress.getHostAddress();

        QuicClientConnectionImpl.Builder builder = QuicClientConnectionImpl.newBuilder();
        return builder.version(Version.IETF_draft_29)
                .noServerCertificateCheck()
                .logger(new SysOutLogger())
                .clientCertificate(selfSignedCertificate.cert())
                .clientCertificateKey(selfSignedCertificate.key())
                .host(host)
                .port(port)
                .build();


    }

    public boolean swarmContains(@NonNull PeerId peerId) {
        return swarm.contains(peerId);
    }

    public List<PeerId> getRelays() {
        return new ArrayList<>(relays);
    }

    public long getShortTime() {
        return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    }

    public void updateNetwork(@NonNull String networkInterface) {
        updateListenAddresses(networkInterface);
    }

    public void updateListenAddresses(@NonNull String networkInterfaceName) {

        try {
            boolean ipv6 = false;
            List<InetAddress> collect = new ArrayList<>();
            List<NetworkInterface> interfaces = Collections.list(
                    NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                if (Objects.equals(networkInterface.getName(), networkInterfaceName)) {

                    List<InetAddress> addresses =
                            Collections.list(networkInterface.getInetAddresses());
                    for (InetAddress inetAddress : addresses) {
                        if (!(inetAddress.isAnyLocalAddress() ||
                                inetAddress.isLinkLocalAddress() ||
                                inetAddress.isLoopbackAddress() ||
                                inetAddress.isSiteLocalAddress())) {
                            if (inetAddress instanceof Inet6Address) {
                                ipv6 = true;
                            }
                            collect.add(inetAddress);
                        }
                    }
                }
            }
            synchronized (TAG.intern()) {
                inet6.set(ipv6);
                addresses.clear();
                addresses.addAll(collect);
            }

            if (addresses.isEmpty()) {
                evaluateDefaultHost();
            }

        } catch (Throwable throwable) {
            LogUtils.error(TAG, throwable);
        }
    }

    public int getPort() {
        return port;
    }

}
