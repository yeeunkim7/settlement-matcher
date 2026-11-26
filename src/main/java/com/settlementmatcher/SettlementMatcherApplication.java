package com.settlementmatcher;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import com.settlementmatcher.launch.Launcher;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SettlementMatcherApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(SettlementMatcherApplication.class);
		AtomicReference<Environment> env = new AtomicReference<>();
		app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) e -> {
			env.set(e.getEnvironment());
			Launcher.exitIfPortBusy(e.getEnvironment());
		});
		try {
			app.run(args);
		} catch (RuntimeException e) {
			PortInUseException portInUse = findPortInUse(e);
			if (portInUse == null) {
				throw e;
			}
			System.exit(Launcher.handlePortInUse(portInUse.getPort(), env.get()));
		}
	}

	private static PortInUseException findPortInUse(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof PortInUseException p) {
				return p;
			}
		}
		return null;
	}

}
