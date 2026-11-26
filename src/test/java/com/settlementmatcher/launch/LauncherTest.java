package com.settlementmatcher.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** 포트가 이미 쓰일 때: 이 앱이면 0(브라우저만 열기), 다른 프로그램이면 1(설정 안내). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LauncherTest {

    @LocalServerPort
    int port;

    private final Environment env = new MockEnvironment().withProperty("server.address", "127.0.0.1");

    @Test
    void 이미_떠_있는_정산_대사면_0() {
        assertThat(Launcher.isThisApp("http://127.0.0.1:" + port)).isTrue();
        assertThat(Launcher.handlePortInUse(port, env)).isEqualTo(0);
    }

    @Test
    void 다른_프로그램이_쓰고_있으면_1() throws Exception {
        try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThat(Launcher.handlePortInUse(other.getLocalPort(), env)).isEqualTo(1);
        }
    }
}
