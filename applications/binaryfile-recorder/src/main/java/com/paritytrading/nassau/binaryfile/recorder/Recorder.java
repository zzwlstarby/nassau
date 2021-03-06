package com.paritytrading.nassau.binaryfile.recorder;

import static org.jvirtanen.util.Applications.*;

import com.paritytrading.nassau.MessageListener;
import com.paritytrading.nassau.binaryfile.BinaryFILEWriter;
import com.paritytrading.nassau.moldudp64.MoldUDP64Client;
import com.paritytrading.nassau.moldudp64.MoldUDP64ClientState;
import com.paritytrading.nassau.moldudp64.MoldUDP64ClientStatusListener;
import com.paritytrading.nassau.soupbintcp.SoupBinTCP;
import com.paritytrading.nassau.soupbintcp.SoupBinTCPClient;
import com.paritytrading.nassau.soupbintcp.SoupBinTCPClientStatusListener;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import org.jvirtanen.config.Configs;

class Recorder {

    private static final int TIMEOUT_MILLIS = 1000;

    private static volatile boolean receive = true;

    public static void main(String[] args) {
        if (args.length != 2)
            usage("nassau-binaryfile-recorder <configuration-file> <output-file>");

        try {
            main(config(args[0]), new File(args[1]));
        } catch (ConfigException | FileNotFoundException e) {
            error(e);
        } catch (IOException e) {
            fatal(e);
        }
    }

    private static void main(Config config, File file) throws IOException {
        addShutdownHook();

        try (final BinaryFILEWriter writer = BinaryFILEWriter.open(file)) {

            MessageListener listener = new MessageListener() {

                @Override
                public void message(ByteBuffer buffer) throws IOException {
                    writer.write(buffer);
                }

            };

            if (config.hasPath("session.multicast-interface")) {
                try (MoldUDP64Client client = join(config, listener)) {
                    receive(client);
                }
            } else {
                try (SoupBinTCPClient client = connect(config, listener)) {
                    receive(client);
                }
            }
        }
    }

    private static SoupBinTCPClient connect(Config config, MessageListener listener) throws IOException {
        InetAddress address  = Configs.getInetAddress(config, "session.address");
        int         port     = Configs.getPort(config, "session.port");
        String      username = config.getString("session.username");
        String      password = config.getString("session.password");

        SocketChannel channel = SocketChannel.open();

        channel.connect(new InetSocketAddress(address, port));
        channel.configureBlocking(false);

        SoupBinTCPClientStatusListener statusListener = new SoupBinTCPClientStatusListener() {

            @Override
            public void heartbeatTimeout(SoupBinTCPClient session) {
                receive = false;
            };

            @Override
            public void loginAccepted(SoupBinTCPClient session, SoupBinTCP.LoginAccepted payload) {
            }

            @Override
            public void loginRejected(SoupBinTCPClient session, SoupBinTCP.LoginRejected payload) {
                receive = false;
            }

            @Override
            public void endOfSession(SoupBinTCPClient session) {
                receive = false;
            }

        };

        SoupBinTCPClient client = new SoupBinTCPClient(channel, listener, statusListener);

        SoupBinTCP.LoginRequest message = new SoupBinTCP.LoginRequest();

        message.setUsername(username);
        message.setPassword(password);
        message.setRequestedSession("");
        message.setRequestedSequenceNumber(1L);

        client.login(message);

        return client;
    }

    private static MoldUDP64Client join(Config config, MessageListener listener) throws IOException {
        NetworkInterface multicastInterface = Configs.getNetworkInterface(config, "session.multicast-interface");
        InetAddress      multicastGroup     = Configs.getInetAddress(config, "session.multicast-group");
        int              multicastPort      = Configs.getPort(config, "session.multicast-port");
        InetAddress      requestAddress     = Configs.getInetAddress(config, "session.request-address");
        int              requestPort        = Configs.getPort(config, "session.request-port");

        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);

        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.bind(new InetSocketAddress(multicastPort));
        channel.join(multicastGroup, multicastInterface);
        channel.configureBlocking(false);

        DatagramChannel requestChannel = DatagramChannel.open(StandardProtocolFamily.INET);

        requestChannel.configureBlocking(false);

        MoldUDP64ClientStatusListener statusListener = new MoldUDP64ClientStatusListener() {

            @Override
            public void state(MoldUDP64Client session, MoldUDP64ClientState next) {
            }

            @Override
            public void downstream(MoldUDP64Client session, long sequenceNumber, int messageCount) {
            }

            @Override
            public void request(MoldUDP64Client session, long sequenceNumber, int requestedMessageCount) {
            }

            @Override
            public void endOfSession(MoldUDP64Client session) {
                receive = false;
            }

        };

        return new MoldUDP64Client(channel, requestChannel,
                new InetSocketAddress(requestAddress, requestPort), listener, statusListener);
    }

    private static void receive(MoldUDP64Client client) throws IOException {
        try (Selector selector = Selector.open()) {

            SelectionKey key = client.getChannel().register(selector, SelectionKey.OP_READ);

            SelectionKey requestKey = client.getRequestChannel().register(selector, SelectionKey.OP_READ);

            while (receive) {
                int numKeys = selector.select();
                if (numKeys > 0) {
                    if (selector.selectedKeys().contains(key))
                        client.receive();

                    if (selector.selectedKeys().contains(requestKey))
                        client.receiveResponse();

                    selector.selectedKeys().clear();
                }
            }
        }
    }

    private static void receive(SoupBinTCPClient client) throws IOException {
        try (Selector selector = Selector.open()) {

            client.getChannel().register(selector, SelectionKey.OP_READ);

            while (receive) {
                int numKeys = selector.select(TIMEOUT_MILLIS);
                if (numKeys > 0) {
                    if (client.receive() < 0)
                        break;

                    selector.selectedKeys().clear();
                }

                client.keepAlive();
            }
        }
    }

    private static void addShutdownHook() {
        final Thread main = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                receive = false;

                try {
                    main.join();
                } catch (InterruptedException e) {
                }
            }

        });
    }

}
