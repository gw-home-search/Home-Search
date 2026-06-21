package com.home.news.infrastructure.external.openai;

import java.util.List;
import java.util.stream.Collectors;

import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalImpactTarget;

record TopicSearchProfile(
	NewsSignalTopic topic,
	List<String> keywords,
	List<SignalImpactTarget> impactTargets,
	List<String> reasonCodes
) {

	static List<TopicSearchProfile> all() {
		return List.of(
			new TopicSearchProfile(NewsSignalTopic.policy_regulation, List.of("부동산 정책", "규제지역", "토지거래허가구역", "분양가상한제"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.volume), List.of("policy", "regulation")),
			new TopicSearchProfile(NewsSignalTopic.tax, List.of("보유세", "양도세", "취득세", "종부세"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.liquidity), List.of("tax")),
			new TopicSearchProfile(NewsSignalTopic.loan_rate, List.of("대출 규제", "DSR", "LTV", "금리", "주담대", "전세대출"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.jeonse_price, SignalImpactTarget.liquidity), List.of("loan", "rate")),
			new TopicSearchProfile(NewsSignalTopic.subscription, List.of("청약", "분양", "특별공급", "무순위"), List.of(SignalImpactTarget.supply, SignalImpactTarget.sale_price), List.of("subscription", "supply")),
			new TopicSearchProfile(NewsSignalTopic.reconstruction_redevelopment, List.of("재건축", "재개발", "정비구역", "안전진단", "조합", "사업시행인가"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.supply), List.of("reconstruction", "redevelopment")),
			new TopicSearchProfile(NewsSignalTopic.supply, List.of("공급", "입주", "입주물량", "착공", "분양물량"), List.of(SignalImpactTarget.supply, SignalImpactTarget.jeonse_price), List.of("supply")),
			new TopicSearchProfile(NewsSignalTopic.transport_infra, List.of("GTX", "지하철", "철도", "역세권", "노선", "개통", "착공"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.volume), List.of("transport", "infra")),
			new TopicSearchProfile(NewsSignalTopic.school_district, List.of("학군", "학교 배정", "교육청", "자사고", "특목고"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.jeonse_price), List.of("school")),
			new TopicSearchProfile(NewsSignalTopic.jeonse_rent, List.of("전세", "월세", "임대차", "전세난", "역전세"), List.of(SignalImpactTarget.jeonse_price, SignalImpactTarget.risk), List.of("jeonse", "rent")),
			new TopicSearchProfile(NewsSignalTopic.transaction_volume, List.of("거래량", "매매거래", "실거래", "거래절벽"), List.of(SignalImpactTarget.volume, SignalImpactTarget.liquidity), List.of("transaction_volume")),
			new TopicSearchProfile(NewsSignalTopic.auction_distress, List.of("경매", "공매", "부실", "연체", "미상환"), List.of(SignalImpactTarget.risk, SignalImpactTarget.sale_price), List.of("auction_distress")),
			new TopicSearchProfile(NewsSignalTopic.unsold_inventory, List.of("미분양", "준공후 미분양", "분양률"), List.of(SignalImpactTarget.supply, SignalImpactTarget.risk), List.of("unsold_inventory")),
			new TopicSearchProfile(NewsSignalTopic.development_project, List.of("개발사업", "복합개발", "신도시", "공공주택지구", "지구지정"), List.of(SignalImpactTarget.sale_price, SignalImpactTarget.supply), List.of("development_project")),
			new TopicSearchProfile(NewsSignalTopic.macro_rate, List.of("기준금리", "한국은행", "채권금리", "가계대출"), List.of(SignalImpactTarget.liquidity, SignalImpactTarget.sale_price), List.of("macro_rate"))
		);
	}

	String line() {
		return "- %s keywords=%s impact_targets=%s reason_codes=%s".formatted(
			topic.name(),
			String.join(", ", keywords),
			impactTargets.stream().map(Enum::name).collect(Collectors.joining(", ")),
			String.join(", ", reasonCodes)
		);
	}
}
