import { PublicFooter } from '../shared/PublicFooter';

export type LegalPageKind = 'privacy' | 'terms' | 'about';

const CONTACT_EMAIL = 'gwangjae.kwon.99@gmail.com';
const EFFECTIVE_DATE = '2026년 8월 6일';

export function LegalPage({ kind }: { kind: LegalPageKind }) {
  return (
    <div className="legal-page">
      <header className="legal-header"><a href="/">홈서치</a></header>
      {kind === 'privacy' ? <PrivacyContent /> : kind === 'terms' ? <TermsContent /> : <AboutContent />}
      <PublicFooter />
    </div>
  );
}

function PrivacyContent() {
  return (
    <main className="legal-document">
      <h1>개인정보처리방침</h1>
      <p>홈서치 운영자(개인)는 이용자의 개인정보를 필요한 범위에서만 처리하고 안전하게 보호합니다.</p>
      <h2>1. 처리하는 정보와 목적</h2>
      <ul>
        <li>Google 로그인: Google 사용자 식별자, 표시 이름, 검증된 이메일, 프로필 이미지 — 본인 확인과 계정 제공</li>
        <li>서비스 이용: 로그인·갱신 시각, 즐겨찾기 및 이메일 알림 설정 — 계정 기능과 선택 기능 제공</li>
        <li>선택적 분석: 기기·브라우저 정보, 접속 페이지, 이용 상호작용, 쿠키·온라인 식별자 — 이용 현황 분석과 품질 개선</li>
      </ul>
      <h2>2. 보유 및 파기</h2>
      <p>회원 탈퇴 시 개인정보를 지체 없이 삭제합니다. 관계 법령에 보존 의무가 있는 정보는 다른 정보와 분리해 법정 기간 동안만 보관한 뒤 삭제합니다.</p>
      <h2>3. Google Analytics</h2>
      <p>분석 쿠키에 동의한 경우에만 Google Analytics를 로드합니다. 관련 정보는 Google LLC의 분석 서비스로 네트워크를 통해 이전되어 처리될 수 있으며, 서비스 이용 통계 작성 목적으로만 사용합니다. 이용자는 화면 하단의 쿠키 설정에서 언제든 허용 또는 거부를 다시 선택할 수 있습니다.</p>
      <h2>4. 이용자의 권리</h2>
      <p>마이페이지에서 계정 정보와 선택 기능을 관리하고 회원 탈퇴를 요청할 수 있습니다. 추가적인 열람·정정·삭제 요청은 아래 이메일로 접수할 수 있습니다.</p>
      <h2>5. 안전성 확보와 문의</h2>
      <p>접근권한 제한, 전송구간 보호, 인증정보 분리 등 합리적인 보호조치를 적용합니다. 개인정보 관련 문의: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a></p>
      <p>시행일: {EFFECTIVE_DATE}</p>
    </main>
  );
}

function TermsContent() {
  return (
    <main className="legal-document">
      <h1>서비스 이용약관</h1>
      <p>이 약관은 홈서치 운영자(개인)가 제공하는 홈서치 서비스의 이용 조건을 정합니다.</p>
      <h2>1. 서비스 내용</h2>
      <p>홈서치는 공개된 부동산 자료를 바탕으로 아파트 실거래가, 단지·지역 정보와 지도 탐색 기능을 제공합니다.</p>
      <h2>2. 정보 이용 시 유의사항</h2>
      <p>제공 정보는 참고 목적이며 매물 중개, 거래 권유, 감정평가 또는 법률·세무·투자 자문이 아닙니다. 중요한 의사결정 전에는 원자료와 관계 기관 정보를 별도로 확인해야 합니다.</p>
      <h2>3. 계정과 이용자의 책임</h2>
      <p>이용자는 자신의 계정을 안전하게 관리하고 관련 법령과 타인의 권리를 침해하는 방식으로 서비스를 이용하지 않아야 합니다.</p>
      <h2>4. 서비스 변경과 제한</h2>
      <p>운영상 또는 기술상 필요한 경우 서비스의 일부를 변경하거나 일시 중단할 수 있습니다. 중대한 변경은 서비스 화면을 통해 알립니다.</p>
      <h2>5. 탈퇴와 문의</h2>
      <p>회원은 마이페이지에서 탈퇴할 수 있습니다. 서비스 문의: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a></p>
      <p>시행일: {EFFECTIVE_DATE}</p>
    </main>
  );
}

function AboutContent() {
  return (
    <main className="legal-document">
      <h1>홈서치 소개</h1>
      <p>홈서치는 지도에서 아파트 실거래가와 지역별 시세를 쉽고 빠르게 탐색할 수 있도록 돕는 개인 운영 정보 서비스입니다.</p>
      <p>공개 데이터를 안전하게 보존하고, 단지와 지역별 정보를 출처에 근거해 이해하기 쉽게 제공하는 것을 목표로 합니다.</p>
      <p>문의: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a></p>
    </main>
  );
}
