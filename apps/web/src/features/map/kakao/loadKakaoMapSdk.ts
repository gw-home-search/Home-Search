export type KakaoLatLng = {
  getLat: () => number;
  getLng: () => number;
};

export type KakaoBounds = {
  getSouthWest: () => KakaoLatLng;
  getNorthEast: () => KakaoLatLng;
};

export type KakaoMap = {
  getBounds: () => KakaoBounds;
  getLevel: () => number;
  setCenter?: (center: KakaoLatLng) => void;
  setLevel?: (level: number) => void;
};

export type KakaoCustomOverlay = {
  setMap: (map: KakaoMap | null) => void;
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
  }) => KakaoCustomOverlay;
  event: {
    addListener: (target: KakaoMap, eventName: string, handler: () => void) => unknown;
    removeListener?: (target: KakaoMap, eventName: string, handler: () => void) => void;
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

          sdkLoadPromise = null;
          reject(new Error('Kakao map SDK did not expose map constructors'));
        });
        return;
      }

      if (isLoadedKakaoMapsApi(maps)) {
        resolve(maps);
        return;
      }

      sdkLoadPromise = null;
      reject(new Error('Kakao map SDK did not expose map constructors'));
    };
    script.onerror = () => {
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
    maps.event !== undefined &&
    typeof maps.event.addListener === 'function'
  );
}
