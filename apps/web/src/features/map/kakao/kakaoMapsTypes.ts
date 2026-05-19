export type KakaoLatLng = {
  getLat(): number;
  getLng(): number;
};

export type KakaoLatLngBounds = {
  getSouthWest(): KakaoLatLng;
  getNorthEast(): KakaoLatLng;
};

export type KakaoMap = {
  getBounds(): KakaoLatLngBounds;
  getLevel(): number;
  setLevel(level: number): void;
  relayout?(): void;
};

export type KakaoEventHandle = unknown;

export type KakaoCustomOverlay = {
  setMap(map: KakaoMap | null): void;
};

export type KakaoMapsApi = {
  Map: new (
    container: HTMLElement,
    options: {
      center: KakaoLatLng;
      level: number;
    },
  ) => KakaoMap;
  LatLng: new (lat: number, lng: number) => KakaoLatLng;
  LatLngBounds: new (southWest: KakaoLatLng, northEast: KakaoLatLng) => KakaoLatLngBounds;
  CustomOverlay: new (options: {
    position: KakaoLatLng;
    content: string | Node;
    yAnchor?: number;
  }) => KakaoCustomOverlay;
  event: {
    addListener(target: KakaoMap, eventName: string, handler: () => void): KakaoEventHandle;
    removeListener(handle: KakaoEventHandle): void;
  };
};

export type KakaoMapsNamespace = KakaoMapsApi & {
  load(callback: () => void): void;
};

declare global {
  interface Window {
    kakao?: {
      maps?: KakaoMapsNamespace;
    };
  }
}
