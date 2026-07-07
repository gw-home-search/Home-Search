package com.home.application.region;

import java.util.List;

@FunctionalInterface
public interface RegionSiGunGuCodeReader {

	List<String> siGunGuCodes();

	static RegionSiGunGuCodeReader empty() {
		return List::of;
	}
}
