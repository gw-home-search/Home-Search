import type { KakaoCustomOverlay, KakaoMap, KakaoMapsApi } from './loadKakaoMapSdk';

export function createKakaoOverlay(
  map: KakaoMap,
  maps: KakaoMapsApi,
  lat: number,
  lng: number,
  zIndex: number,
  content: HTMLElement,
): KakaoCustomOverlay {
  const overlay = new maps.CustomOverlay({
    position: new maps.LatLng(lat, lng),
    content,
    yAnchor: 1,
    zIndex,
  });
  overlay.setMap(map);
  return overlay;
}

export function clearKakaoOverlays(overlays: Iterable<KakaoCustomOverlay>): void {
  for (const overlay of overlays) overlay.setMap(null);
}
