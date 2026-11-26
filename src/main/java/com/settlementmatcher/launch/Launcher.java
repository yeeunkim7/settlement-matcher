package com.settlementmatcher.launch;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 고객 PC에서 실행.bat으로 띄웠을 때의 안내와 브라우저 열기.
 * <ul>
 * <li>준비가 끝나면 접속 주소를 안내하고, {@code app.open-browser=true}이면 실제 포트로 브라우저를 연다.</li>
 * <li>포트가 이미 쓰이고 있으면 {@link #handlePortInUse}가 원인을 가려 안내한다.</li>
 * </ul>
 */
@Component
public class Launcher {

    /** index.html의 {@code <meta name="application-name">} 값. 포트를 쓰는 쪽이 이 앱인지 가리는 데 쓴다. */
    static final String MARKER = "content=\"settlement-matcher\"";

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    private final Environment env;

    public Launcher(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        Integer port = env.getProperty("local.server.port", Integer.class);
        if (port == null) {
            return; // 웹 서버 없이 뜬 경우(테스트 등)
        }
        String url = url(env, port);
        System.out.println();
        System.out.println("  정산 대사가 실행되었어요: " + url);
        System.out.println("  이 창을 닫으면 프로그램이 종료돼요.");
        System.out.println();
        if (env.getProperty("app.open-browser", Boolean.class, false)) {
            openBrowser(url);
        }
    }

    /**
     * 포트가 이미 쓰일 때. 이 앱이 이미 떠 있으면(실행.bat을 두 번 누른 경우) 브라우저만 열고 0을,
     * 다른 프로그램이 쓰고 있으면 바꿀 설정을 안내하고 1을 돌려준다.
     */
    public static int handlePortInUse(int port, Environment env) {
        String url = url(env, port);
        System.out.println();
        if (isThisApp(url)) {
            System.out.println("  정산 대사가 이미 실행 중이에요: " + url);
            System.out.println("  브라우저에서 바로 쓰시면 돼요. 이 창은 닫아도 돼요.");
            if (env != null && env.getProperty("app.open-browser", Boolean.class, false)) {
                openBrowser(url);
            }
            return 0;
        }
        System.out.println("  " + port + " 포트를 다른 프로그램이 쓰고 있어서 시작하지 못했어요.");
        System.out.println("  같은 폴더의 application.yml에서 server.port 값을 " + (port + 1)
                + "처럼 다른 숫자로 바꾼 뒤 다시 실행해 주세요.");
        return 1;
    }

    /**
     * 설정을 읽은 직후(웹 서버를 띄우기 전)에 포트를 미리 잡아 본다. 쓰이고 있으면 Spring의 영어 오류 대신
     * {@link #handlePortInUse} 안내만 보여 주고 끝낸다. 이 확인과 실제 시작 사이에 포트를 뺏기는 경우는
     * main()의 예외 처리가 같은 안내를 보여 준다.
     */
    public static void exitIfPortBusy(Environment env) {
        int port = env.getProperty("server.port", Integer.class, 8080);
        if (port <= 0) {
            return;
        }
        String address = env.getProperty("server.address");
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(address == null || address.isBlank() ? new InetSocketAddress(port)
                    : new InetSocketAddress(address, port));
        } catch (IOException e) {
            System.exit(handlePortInUse(port, env));
        }
    }

    static String url(Environment env, int port) {
        String address = env == null ? null : env.getProperty("server.address");
        if (address == null || address.isBlank() || address.equals("0.0.0.0")) {
            address = "127.0.0.1";
        }
        return "http://" + address + ":" + port;
    }

    static boolean isThisApp(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url + "/").toURL().openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try (InputStream in = c.getInputStream()) {
                String head = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
                return head.contains(MARKER);
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static void openBrowser(String url) {
        // Spring Boot는 headless로 뜨므로 java.awt.Desktop 대신 OS 명령으로 기본 브라우저를 연다.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : os.contains("mac") ? new String[] {"open", url}
                : new String[] {"xdg-open", url};
        try {
            // 콘솔 핸들을 물려주지 않는다. 물려주면 브라우저가 실행.bat 창의 출력을 붙잡고 있게 된다.
            new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            log.warn("브라우저를 열지 못했어요. 직접 {} 에 접속해 주세요. ({})", url, e.getMessage());
        }
    }
}
