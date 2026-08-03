package com.laioffer.travelplanner.config;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

/**
 * Exposes the in-memory database over TCP so an external tool — IntelliJ's Database window, DataGrip,
 * DBeaver — can browse it while the app runs.
 *
 * <p>Without this there is nothing to connect to: an embedded H2 database lives inside this JVM and is
 * reached by method call, not by socket. Starting H2's TCP server in the same JVM publishes the very
 * same in-memory database at {@code jdbc:h2:tcp://localhost:9092/mem:travelplanner}.
 *
 * <p>The listener binds to loopback only — H2 requires the explicit {@code -tcpAllowOthers} flag to
 * accept remote connections, which is deliberately not passed. Set
 * {@code travelplanner.h2.tcp.enabled: false} to switch it off entirely.
 */
@Configuration
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(name = "travelplanner.h2.tcp.enabled", matchIfMissing = true)
public class H2TcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServerConfig.class);

    @Bean(initMethod = "start", destroyMethod = "stop")
    public Server h2TcpServer(@Value("${travelplanner.h2.tcp.port:9092}") String port,
                              @Value("${spring.datasource.url}") String datasourceUrl) throws SQLException {
        String name = datasourceUrl.startsWith("jdbc:h2:mem:")
                ? datasourceUrl.substring("jdbc:h2:mem:".length()).split(";")[0]
                : "<see spring.datasource.url>";
        log.info("H2 TCP server on port {} — connect an external client to "
                + "jdbc:h2:tcp://localhost:{}/mem:{} (user sa, no password)", port, port, name);
        return Server.createTcpServer("-tcpPort", port, "-tcpDaemon");
    }
}
