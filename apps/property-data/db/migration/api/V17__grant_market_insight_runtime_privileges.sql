GRANT SELECT, INSERT, UPDATE
ON TABLE public.rtms_collection_execution,
         public.rtms_collection_work_unit,
         public.market_insight_snapshot,
         public.market_insight_trade_item
TO home_search_property_runtime;
