package com.home.infrastructure.external.prediction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.home.application.prediction.PredictionClient;
import com.home.application.prediction.PredictionClientResult;
import com.home.application.prediction.PredictionRequest;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PythonPredictionClient implements PredictionClient {

	private final RestClient restClient;

	public PythonPredictionClient(RestClient restClient) {
		this.restClient = Objects.requireNonNull(restClient);
	}

	@Override
	public PredictionClientResult predict(PredictionRequest request) {
		try {
			PythonPredictionResponse response = restClient.post()
				.uri("/predict")
				.body(request)
				.retrieve()
				.body(PythonPredictionResponse.class);
			if (response == null) {
				throw new IllegalStateException("empty prediction response");
			}
			return response.toResult();
		} catch (RestClientException ex) {
			throw new IllegalStateException("prediction service unavailable", ex);
		}
	}

	private record PythonPredictionResponse(
		String modelVersion,
		BigDecimal predictedPricePerM2,
		BigDecimal predictedDealAmount,
		BigDecimal predictedPricePerPyeong,
		BigDecimal rawResidualLog,
		BigDecimal predictedLogPricePerM2,
		BigDecimal intervalLow,
		BigDecimal intervalHigh,
		String intervalBasis
	) {

		private PredictionClientResult toResult() {
			return new PredictionClientResult(
				modelVersion,
				predictedPricePerM2,
				roundedLong(predictedDealAmount),
				predictedPricePerPyeong,
				rawResidualLog,
				predictedLogPricePerM2,
				roundedLong(intervalLow),
				roundedLong(intervalHigh),
				intervalBasis
			);
		}

		private static Long roundedLong(BigDecimal value) {
			return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
		}
	}
}
