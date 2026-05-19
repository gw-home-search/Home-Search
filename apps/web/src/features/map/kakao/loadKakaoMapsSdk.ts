import type { KakaoMapsApi, KakaoMapsNamespace } from './kakaoMapsTypes';

const KAKAO_MAPS_SCRIPT_ID = 'kakao-maps-sdk';
const KAKAO_MAPS_SDK_BASE_URL = 'https://dapi.kakao.com/v2/maps/sdk.js';

let sdkPromise: Promise<KakaoMapsApi> | null = null;

export function loadKakaoMapsSdk(): Promise<KakaoMapsApi> {
  const appKey = (import.meta.env.VITE_KAKAO_MAP_APP_KEY as string | undefined)?.trim();
  if (!appKey) {
    return Promise.reject(new Error('VITE_KAKAO_MAP_APP_KEY is required to load Kakao map'));
  }

  if (window.kakao?.maps?.load) {
    return waitForKakaoMapsLoad(window.kakao.maps);
  }

  if (sdkPromise) {
    return sdkPromise;
  }

  sdkPromise = new Promise<KakaoMapsApi>((resolve, reject) => {
    const existingScript = document.getElementById(
      KAKAO_MAPS_SCRIPT_ID,
    ) as HTMLScriptElement | null;
    const script = existingScript ?? document.createElement('script');

    const fail = () => {
      sdkPromise = null;
      reject(new Error('Failed to load Kakao Maps SDK script'));
    };

    const finish = () => {
      waitForKakaoMapsLoad(window.kakao?.maps)
        .then(resolve)
        .catch((error: unknown) => {
          sdkPromise = null;
          reject(error);
        });
    };

    script.addEventListener('load', finish, { once: true });
    script.addEventListener('error', fail, { once: true });

    if (!existingScript) {
      script.id = KAKAO_MAPS_SCRIPT_ID;
      script.async = true;
      script.src = kakaoMapsSdkUrl(appKey);
      document.head.appendChild(script);
    }
  });

  return sdkPromise;
}

function kakaoMapsSdkUrl(appKey: string): string {
  return `${KAKAO_MAPS_SDK_BASE_URL}?appkey=${encodeURIComponent(
    appKey,
  )}&autoload=false&libraries=services,clusterer`;
}

function waitForKakaoMapsLoad(maps: KakaoMapsNamespace | undefined): Promise<KakaoMapsApi> {
  if (!maps?.load) {
    return Promise.reject(new Error('Kakao Maps SDK did not expose kakao.maps.load'));
  }

  return new Promise<KakaoMapsApi>((resolve, reject) => {
    try {
      maps.load(() => {
        const loadedMaps = window.kakao?.maps;
        if (!loadedMaps?.Map || !loadedMaps.LatLng || !loadedMaps.CustomOverlay) {
          reject(new Error('Kakao Maps SDK loaded without required map constructors'));
          return;
        }

        resolve(loadedMaps);
      });
    } catch (error) {
      reject(error instanceof Error ? error : new Error('Kakao Maps SDK load callback failed'));
    }
  });
}
