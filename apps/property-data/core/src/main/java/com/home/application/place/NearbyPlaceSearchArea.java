package com.home.application.place;

public sealed interface NearbyPlaceSearchArea permits NearbyPlaceRadiusArea, NearbyPlaceBoundsArea {

    NearbyPlacePoint center();
}
