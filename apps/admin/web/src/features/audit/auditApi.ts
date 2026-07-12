import { adminRequest } from '../../shared/http/adminHttp';
export type AuditEvent = { id:number; actorAccountId:string|null; targetAccountId:string|null; eventType:string; requestId:string|null; success:boolean; createdAt:string };
export function fetchAuditEvents(limit = 50, offset = 0) { return adminRequest<AuditEvent[]>(`/api/v1/admin/audit?limit=${limit}&offset=${offset}`); }
