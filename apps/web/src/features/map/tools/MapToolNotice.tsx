export function MapToolNotice({ cadastralEnabled }: { cadastralEnabled: boolean }) {
  return cadastralEnabled ? (
    <p className="map-density-note map-cadastral-notice" role="status">
      지적편집도는 참고용이며 실제 지적 정보와 다를 수 있습니다.
    </p>
  ) : null;
}
