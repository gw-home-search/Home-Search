import type { SeoPage } from './types';

export function SeoLandingContent({page}:{page:SeoPage}) {
  return <main className="seo-landing">
    <nav aria-label="지역 경로"><a href="/">홈</a>{page.data.breadcrumbs.map((item)=><span key={item.regionId}> / <a href={`/regions/${item.regionId}`}>{item.name}</a></span>)}</nav>
    <h1>{page.kind==='complex'?`${page.data.name} 실거래가·단지정보`:`${page.data.name} 아파트 실거래가`}</h1>
    {page.kind==='complex'?<ComplexBody data={page.data}/>:<RegionBody data={page.data}/>} 
    <p><a href="/">홈서치 지도에서 보기</a></p>
  </main>;
}

function ComplexBody({data}:{data:Extract<SeoPage,{kind:'complex'}>['data']}) { return <>
  <p>{data.address}에 있는 {data.name}의 최근 아파트 실거래가와 단지정보입니다.</p>
  <dl>{data.dongCount!=null&&<><dt>동 수</dt><dd>{data.dongCount.toLocaleString('ko-KR')}개 동</dd></>}{data.unitCount!=null&&<><dt>세대 수</dt><dd>{data.unitCount.toLocaleString('ko-KR')}세대</dd></>}{data.useApprovalDate!=null&&<><dt>사용승인일</dt><dd>{data.useApprovalDate}</dd></>}</dl>
  <h2>최근 실거래</h2>{data.recentTrades.length===0?<p>공개된 최근 실거래가 없습니다.</p>:<ul>{data.recentTrades.map((trade,index)=><li key={`${trade.dealDate}-${trade.dealAmount}-${index}`}>{trade.dealDate} · {trade.dealAmount.toLocaleString('ko-KR')}만원{trade.exclusiveArea==null?'':` · 전용 ${trade.exclusiveArea}㎡`}{trade.floor==null?'':` · ${trade.floor}층`}</li>)}</ul>}
  </>; }
function RegionBody({data}:{data:Extract<SeoPage,{kind:'region'}>['data']}) { return <>
  <p>{data.name}의 아파트 실거래가와 대표 단지를 확인하세요. 색인 가능한 단지는 {data.indexableComplexCount.toLocaleString('ko-KR')}곳입니다.</p>
  <h2>대표 단지</h2>{data.representativeComplexes.length===0?<p>공개할 수 있는 대표 단지가 아직 없습니다.</p>:<ul>{data.representativeComplexes.map((complex)=><li key={complex.complexId}><a href={`/complexes/${complex.complexId}`}>{complex.name}</a> · {complex.address}</li>)}</ul>}
  </>; }
