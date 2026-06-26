package am.ik.mcp.fetch.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Minimal HTTP Basic authentication for the playground pages, implemented as a
 * {@link HandlerInterceptor} so it does not require Spring Security. It only guards the
 * routes it is registered for; the MCP endpoint, actuator, and static assets are left
 * untouched. On a missing or invalid credential it responds with {@code 401} and a
 * {@code WWW-Authenticate} challenge so browsers show the native login dialog.
 */
public class PlaygroundBasicAuthInterceptor implements HandlerInterceptor {

	private static final String BASIC_PREFIX = "Basic ";

	private final PlaygroundAuthProperties properties;

	public PlaygroundBasicAuthInterceptor(PlaygroundAuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!this.properties.enabled() || isAuthenticated(request)) {
			return true;
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
				"Basic realm=\"" + this.properties.realm() + "\", charset=\"UTF-8\"");
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		return false;
	}

	private boolean isAuthenticated(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BASIC_PREFIX)) {
			return false;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).strip()),
					StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			if (colon < 0) {
				return false;
			}
			String user = decoded.substring(0, colon);
			String password = decoded.substring(colon + 1);
			return constantTimeEquals(user, this.properties.username())
					&& constantTimeEquals(password, this.properties.password());
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

}
