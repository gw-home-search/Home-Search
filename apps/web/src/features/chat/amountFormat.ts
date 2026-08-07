export function formatTenThousandKrw(amount: number): string {
  const eok = Math.floor(amount / 10_000);
  const remainder = amount % 10_000;
  if (eok > 0 && remainder > 0) {
    return `${eok.toLocaleString('ko-KR')}억 ${remainder.toLocaleString('ko-KR')}만원`;
  }
  if (eok > 0) return `${eok.toLocaleString('ko-KR')}억원`;
  return `${remainder.toLocaleString('ko-KR')}만원`;
}
