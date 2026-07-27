const SQUARE_METERS_PER_PYEONG = 3.305785;

export function formatExactArea(value: number): string {
  return `${value.toLocaleString('ko-KR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}㎡`;
}

export function formatApproxPyeong(value: number): string {
  return `약 ${(value / SQUARE_METERS_PER_PYEONG).toLocaleString('ko-KR', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })}평`;
}

export function formatLargeArea(value: number): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}㎡`;
}

export function formatDecimal(value: number, suffix: string): string {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}${suffix}`;
}

export function formatAmount(amount: number | null): string {
  if (amount == null) return '최근 거래 없음';
  if (amount < 10000) return `${amount.toLocaleString('ko-KR')}만원`;
  const eok = Math.floor(amount / 10000);
  const man = amount % 10000;
  return man === 0
    ? `${eok.toLocaleString('ko-KR')}억`
    : `${eok.toLocaleString('ko-KR')}억 ${man.toLocaleString('ko-KR')}만원`;
}

export function formatEokAxis(amount: number): string {
  return `${(amount / 10000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}억`;
}
