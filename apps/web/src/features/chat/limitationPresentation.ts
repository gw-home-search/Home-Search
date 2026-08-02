export function isWarningLimitation(text: string): boolean {
  return /(못|없|제외|불가|아니|않|미만|부족|중단|실패|미지원)/.test(text);
}

export function isDataNote(text: string): boolean {
  return /(신고|직선거리|좌표|표본|기준일|관찰|갱신|coverage|커버리지|취소|지연|차이|대체|오래)/i.test(text);
}
