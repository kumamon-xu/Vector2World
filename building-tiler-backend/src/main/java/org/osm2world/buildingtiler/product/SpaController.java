package org.osm2world.buildingtiler.product;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class SpaController {
	@GetMapping({ "/", "/import", "/configure", "/preview", "/generate" })
	public String application() { return "forward:/index.html"; }
}
