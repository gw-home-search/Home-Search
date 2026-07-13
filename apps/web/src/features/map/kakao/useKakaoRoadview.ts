import { useEffect } from 'react';

import type { MapPoint, RoadviewRuntimeState } from '../tools/mapToolTypes';
import type {
  KakaoMap,
  KakaoMapMouseEvent,
  KakaoMapsApi,
  KakaoLatLng,
} from './loadKakaoMapSdk';

type UseKakaoRoadviewArgs = {
  active: boolean;
  initialPoint: MapPoint | null;
  map: KakaoMap | null;
  maps: KakaoMapsApi | null;
  roadviewHost: HTMLElement | null;
  onStateChange: (state: RoadviewRuntimeState) => void;
};

export function useKakaoRoadview({
  active,
  initialPoint,
  map,
  maps,
  roadviewHost,
  onStateChange,
}: UseKakaoRoadviewArgs) {
  useEffect(() => {
    if (!active || !map || !maps || !roadviewHost) {
      return undefined;
    }

    let disposed = false;
    let requestSequence = 0;
    const savedCenter = map.getCenter?.();
    const roadview = new maps.Roadview(roadviewHost);
    const client = new maps.RoadviewClient();
    const initialPosition = initialPoint
      ? new maps.LatLng(initialPoint.lat, initialPoint.lng)
      : map.getCenter?.();

    if (!initialPosition) {
      onStateChange('error');
      return undefined;
    }

    const marker = new maps.Marker({ map, position: initialPosition });

    function loadAt(position: KakaoLatLng) {
      requestSequence += 1;
      const requestId = requestSequence;
      onStateChange('loading');
      marker.setPosition(position);
      client.getNearestPanoId(position, 100, (panoId) => {
        if (disposed || requestId !== requestSequence) return;
        if (panoId == null) {
          onStateChange('unavailable');
          return;
        }
        roadview.setPanoId(panoId, position);
        onStateChange('ready');
      });
    }

    const clickHandler = (event: KakaoMapMouseEvent) => loadAt(event.latLng);

    map.addOverlayMapTypeId(maps.MapTypeId.ROADVIEW);
    maps.event.addListener(map, 'click', clickHandler as (...args: never[]) => void);
    loadAt(initialPosition);

    return () => {
      disposed = true;
      maps.event.removeListener?.(map, 'click', clickHandler as (...args: never[]) => void);
      map.removeOverlayMapTypeId(maps.MapTypeId.ROADVIEW);
      marker.setMap(null);
      if (savedCenter) {
        requestAnimationFrame(() => {
          map.relayout?.();
          map.setCenter?.(savedCenter);
        });
      }
    };
  }, [active, initialPoint, map, maps, onStateChange, roadviewHost]);
}
