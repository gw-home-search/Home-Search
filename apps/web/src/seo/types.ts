export type SeoBreadcrumb = { regionId: number; name: string };
export type SeoComplexData = { complexId:number; name:string; address:string; indexable:boolean; dongCount:number|null; unitCount:number|null; useApprovalDate:string|null; hasBuildingInfo:boolean; breadcrumbs:SeoBreadcrumb[]; recentTrades:Array<{dealDate:string;dealAmount:number;exclusiveArea:number|null;floor:number|null}> };
export type SeoRegionData = { regionId:number; name:string; indexable:boolean; indexableComplexCount:number; breadcrumbs:SeoBreadcrumb[]; representativeComplexes:Array<{complexId:number;name:string;address:string}> };
export type SeoPage = {kind:'complex';canonicalOrigin:string;data:SeoComplexData}|{kind:'region';canonicalOrigin:string;data:SeoRegionData};
