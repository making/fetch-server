package am.ik.mcp.fetch;

import java.time.InstantSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebFetchProperties.class)
public class AppConfig {

	@Bean
	public RestClientCustomizer restClientCustomizer(Logbook logbook) {
		InetAddressFilter onlyExternalAddresses = InetAddressFilter.externalAddresses();
		HttpClientSettings settings = HttpClientSettings.defaults().withInetAddressFilter(onlyExternalAddresses);
		return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
			.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook));
	}

	@Bean
	public InstantSource instantSource() {
		return InstantSource.system();
	}

}
