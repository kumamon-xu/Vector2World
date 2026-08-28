package org.osm2world.buildingtiler.application;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.TilingConfig;

public record GenerationJobSpec(
		String datasetId,
		HeightMapping heightMapping,
		ModelingConfig modelingConfig,
		TilingConfig tilingConfig,
		DeliveryPolicy deliveryPolicy) {

	public GenerationJobSpec {
		if (datasetId == null || datasetId.isBlank() || heightMapping == null
				|| modelingConfig == null || tilingConfig == null) {
			throw new IllegalArgumentException("datasetId, heightMapping, modelingConfig and tilingConfig are required");
		}
		deliveryPolicy = deliveryPolicy == null ? DeliveryPolicy.requireComplete() : deliveryPolicy;
	}

	public GenerationJobSpec(String datasetId, HeightMapping heightMapping,
			ModelingConfig modelingConfig, TilingConfig tilingConfig) {
		this(datasetId, heightMapping, modelingConfig, tilingConfig, DeliveryPolicy.requireComplete());
	}
}
