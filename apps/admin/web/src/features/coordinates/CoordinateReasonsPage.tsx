const guides = [
  {
    title: 'PNU 좌표 없음',
    code: 'PNU_COORDINATE_MISSING',
    description: '주소 또는 승인된 외부 좌표원으로 PNU 위치를 확인한 뒤 수동 override를 적용합니다.',
  },
  {
    title: '동일 PNU 다중 단지',
    code: 'SAME_PNU_MULTI_COMPLEX',
    description: '단지 구분이 사라지므로 parcel 좌표를 덮어쓰지 않습니다. 단지별 표시 좌표 보강 대상으로 남깁니다.',
  },
  {
    title: '단지 표시 좌표 없음',
    code: 'COMPLEX_DISPLAY_COORDINATE_MISSING',
    description: '기존 parcel 좌표 대신 해당 complex의 표시 좌표를 별도로 보강합니다.',
  },
];

export function CoordinateReasonsPage() {
  return <article className="panel"><div className="panel-title"><div><p className="eyebrow">OPERATING GUIDE</p><h3>좌표 보강 사유</h3></div><a href="/admin/coordinates">대기 목록으로</a></div>
    <p>사유마다 데이터 소유권과 허용되는 작업이 다릅니다.</p>
    <div className="reason-grid">{guides.map(guide => <section key={guide.code}><span>{guide.code}</span><h4>{guide.title}</h4><p>{guide.description}</p></section>)}</div>
  </article>;
}
