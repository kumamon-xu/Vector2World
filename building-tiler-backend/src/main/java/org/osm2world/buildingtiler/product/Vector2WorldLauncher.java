package org.osm2world.buildingtiler.product;

import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.Vector2WorldApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

public final class Vector2WorldLauncher {

	private Vector2WorldLauncher() { }

	public static void main(String[] args) throws Exception {
		LaunchOptions options = LaunchOptions.parse(args);
		ProductDataLayout layout = ProductDataLayout.open(options.dataRoot(), options.instanceId());
		Map<String, Object> defaults = new LinkedHashMap<>();
		defaults.put("server.address", "127.0.0.1");
		defaults.put("server.port", "0");
		defaults.put("vector2world.datasets.storage-root", layout.datasets().toString());
		defaults.put("vector2world.previews.storage-root", layout.previews().toString());
		defaults.put("vector2world.jobs.storage-root", layout.jobs().toString());
		defaults.put("logging.file.name", layout.logs().resolve("vector2world.log").toString());
		defaults.put("vector2world.product.root", layout.root().toString());

		SpringApplication application = new SpringApplication(Vector2WorldApplication.class);
		application.setDefaultProperties(defaults);
		ConfigurableApplicationContext context = application.run(options.springArguments());
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		URI uri = URI.create("http://127.0.0.1:" + port + "/");
		ProductBuildInfo build = ProductBuildInfo.current();
		System.out.printf("Vector2World %s build %s git %s%s%n", build.version(), build.buildNumber(),
				build.gitSha(), build.gitDirty() ? "-dirty" : "");
		System.out.println("VECTOR2WORLD_DATA " + layout.instance());
		System.out.println("VECTOR2WORLD_READY " + uri);

		if (options.smokeExit()) {
			assertHealthy(uri.resolve("api/system/health"));
			context.close();
			System.out.println("VECTOR2WORLD_SMOKE_OK");
			return;
		}
		installTray(context, uri);
		if (!options.noBrowser()) openBrowser(uri);
	}

	private static void assertHealthy(URI uri) throws Exception {
		HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
				.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
						HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
			throw new IllegalStateException("Packaged health check failed: HTTP " + response.statusCode());
		}
	}

	private static void openBrowser(URI uri) {
		try {
			if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri);
		} catch (Exception exception) {
			System.err.println("Open this address in a browser: " + uri + " (" + exception.getMessage() + ")");
		}
	}

	private static void installTray(ConfigurableApplicationContext context, URI uri) {
		if (!SystemTray.isSupported()) return;
		try {
			BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			graphics.setColor(new java.awt.Color(7, 23, 34)); graphics.fillRect(0, 0, 32, 32);
			graphics.setColor(new java.awt.Color(82, 221, 208)); graphics.fillRect(6, 6, 20, 20);
			graphics.setColor(new java.awt.Color(7, 23, 34)); graphics.fillRect(11, 11, 10, 10);
			graphics.dispose();
			PopupMenu menu = new PopupMenu();
			MenuItem open = new MenuItem("Open Vector2World");
			open.addActionListener(event -> openBrowser(uri));
			MenuItem exit = new MenuItem("Exit");
			exit.addActionListener(event -> { context.close(); System.exit(0); });
			menu.add(open); menu.addSeparator(); menu.add(exit);
			TrayIcon icon = new TrayIcon(image, "Vector2World " + ProductBuildInfo.current().version(), menu);
			icon.setImageAutoSize(true);
			icon.addActionListener(event -> openBrowser(uri));
			SystemTray.getSystemTray().add(icon);
		} catch (Exception exception) {
			System.err.println("System tray is unavailable: " + exception.getMessage());
		}
	}

	private record LaunchOptions(boolean noBrowser, boolean smokeExit, Path dataRoot,
			String instanceId, String[] springArguments) {
		static LaunchOptions parse(String[] args) {
			boolean noBrowser = false;
			boolean smokeExit = false;
			Path dataRoot = null;
			String instanceId = null;
			List<String> spring = new ArrayList<>();
			for (String argument : args) {
				if ("--no-browser".equals(argument)) noBrowser = true;
				else if ("--smoke-exit".equals(argument)) { noBrowser = true; smokeExit = true; }
				else if (argument.startsWith("--data-root=")) dataRoot = Path.of(argument.substring(12));
				else if (argument.startsWith("--instance-id=")) instanceId = argument.substring(14);
				else spring.add(argument);
			}
			return new LaunchOptions(noBrowser, smokeExit, dataRoot, instanceId, spring.toArray(String[]::new));
		}
	}
}
