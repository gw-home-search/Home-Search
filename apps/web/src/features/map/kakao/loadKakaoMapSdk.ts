export type KakaoLatLng = {
  getLat: () => number;
  getLng: () => number;
};

export type KakaoBounds = {
  getSouthWest: () => KakaoLatLng;
  getNorthEast: () => KakaoLatLng;
};

export type KakaoMapTypeId = string | number;

export type KakaoMap = {
  addOverlayMapTypeId: (mapTypeId: KakaoMapTypeId) => void;
  getBounds: () => KakaoBounds;
  getCenter?: () => KakaoLatLng;
  getLevel: () => number;
  relayout?: () => void;
  removeOverlayMapTypeId: (mapTypeId: KakaoMapTypeId) => void;
  setCenter?: (center: KakaoLatLng) => void;
  setLevel?: (level: number) => void;
  setMapTypeId: (mapTypeId: KakaoMapTypeId) => void;
  setMaxLevel?: (level: number) => void;
  setMinLevel?: (level: number) => void;
};

export type KakaoCustomOverlay = {
  setMap: (map: KakaoMap | null) => void;
};

export type KakaoMarker = {
  setMap: (map: KakaoMap | null) => void;
  setPosition: (position: KakaoLatLng) => void;
};

export type KakaoPolyline = {
  getLength: () => number;
  setMap: (map: KakaoMap | null) => void;
  setPath: (path: KakaoLatLng[]) => void;
};

export type KakaoRoadview = {
  setPanoId: (panoId: number, position: KakaoLatLng) => void;
};

export type KakaoMapMouseEvent = {
  latLng: KakaoLatLng;
};

export type KakaoMapsApi = {
  LatLng: new (lat: number, lng: number) => KakaoLatLng;
  Map: new (
    container: HTMLElement,
    options: {
      center: KakaoLatLng;
      level: number;
    },
  ) => KakaoMap;
  CustomOverlay: new (options: {
    position: KakaoLatLng;
    content: HTMLElement | string;
    yAnchor?: number;
    zIndex?: number;
  }) => KakaoCustomOverlay;
  Marker: new (options: {
    map?: KakaoMap;
    position: KakaoLatLng;
  }) => KakaoMarker;
  Polyline: new (options: {
    map?: KakaoMap;
    path: KakaoLatLng[];
    strokeColor?: string;
    strokeOpacity?: number;
    strokeWeight?: number;
  }) => KakaoPolyline;
  Roadview: new (container: HTMLElement) => KakaoRoadview;
  RoadviewClient: new () => {
    getNearestPanoId: (
      position: KakaoLatLng,
      radius: number,
      callback: (panoId: number | null) => void,
    ) => void;
  };
  MapTypeId: {
    HYBRID: KakaoMapTypeId;
    ROADMAP: KakaoMapTypeId;
    ROADVIEW: KakaoMapTypeId;
    TERRAIN: KakaoMapTypeId;
    USE_DISTRICT: KakaoMapTypeId;
  };
  event: {
    addListener: (target: KakaoMap | KakaoRoadview, eventName: string, handler: (...args: never[]) => void) => unknown;
    removeListener?: (target: KakaoMap | KakaoRoadview, eventName: string, handler: (...args: never[]) => void) => void;
  };
  load?: (callback: () => void) => void;
};

type KakaoGlobal = {
  maps?: KakaoMapsApi;
};

declare global {
  var kakao: KakaoGlobal | undefined;
}

let sdkLoadPromise: Promise<KakaoMapsApi> | null = null;

export function loadKakaoMapSdk(appKey: string): Promise<KakaoMapsApi> {
  const existingMaps = globalThis.kakao?.maps;
  if (isLoadedKakaoMapsApi(existingMaps)) {
    return Promise.resolve(existingMaps);
  }

  const trimmedAppKey = appKey.trim();
  if (trimmedAppKey.length === 0) {
    return Promise.reject(new Error('VITE_KAKAO_MAP_APP_KEY is not configured'));
  }

  if (sdkLoadPromise) {
    return sdkLoadPromise;
  }

  sdkLoadPromise = new Promise<KakaoMapsApi>((resolve, reject) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(
      trimmedAppKey,
    )}&autoload=false`;
    script.onload = () => {
      const maps = globalThis.kakao?.maps;
      if (!maps) {
        script.remove();
        sdkLoadPromise = null;
        reject(new Error('Kakao map SDK did not expose kakao.maps'));
        return;
      }

      if (typeof maps.load === 'function') {
        maps.load(() => {
          if (isLoadedKakaoMapsApi(maps)) {
            resolve(maps);
            return;
          }

          script.remove();
          sdkLoadPromise = null;
          reject(new Error('Kakao map SDK did not expose map constructors'));
        });
        return;
      }

      if (isLoadedKakaoMapsApi(maps)) {
        resolve(maps);
        return;
      }

      script.remove();
      sdkLoadPromise = null;
      reject(new Error('Kakao map SDK did not expose map constructors'));
    };
    script.onerror = () => {
      script.remove();
      sdkLoadPromise = null;
      reject(new Error('Kakao map SDK failed to load'));
    };

    document.head.appendChild(script);
  });

  return sdkLoadPromise;
}

function isLoadedKakaoMapsApi(maps: KakaoMapsApi | undefined): maps is KakaoMapsApi {
  return (
    maps !== undefined &&
    typeof maps.LatLng === 'function' &&
    typeof maps.Map === 'function' &&
    typeof maps.CustomOverlay === 'function' &&
    typeof maps.Marker === 'function' &&
    typeof maps.Polyline === 'function' &&
    typeof maps.Roadview === 'function' &&
    typeof maps.RoadviewClient === 'function' &&
    maps.MapTypeId !== undefined &&
    maps.MapTypeId.ROADMAP !== undefined &&
    maps.MapTypeId.HYBRID !== undefined &&
    maps.MapTypeId.TERRAIN !== undefined &&
    maps.MapTypeId.ROADVIEW !== undefined &&
    maps.MapTypeId.USE_DISTRICT !== undefined &&
    maps.event !== undefined &&
    typeof maps.event.addListener === 'function'
  );
}
