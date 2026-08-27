package org.osm2world.buildingtiler.product;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ProductWebConfiguration implements WebMvcConfigurer {
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
		registry.addResourceHandler("/assets/**")
				.addResourceLocations("classpath:/static/assets/").setCacheControl(immutable);
		registry.addResourceHandler("/cesiumStatic/**")
				.addResourceLocations("classpath:/static/cesiumStatic/").setCacheControl(immutable);
	}
}
