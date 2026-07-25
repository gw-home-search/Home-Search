GRANT SELECT, INSERT, UPDATE, DELETE
ON TABLE public.market_news_collection_execution,
         public.market_news_collection_work_unit,
         public.market_news_raw_item,
         public.market_news_article,
         public.market_news_relation,
         public.market_news_snapshot,
         public.market_news_snapshot_item,
         public.market_news_major_complex_selection,
         public.market_news_quality_label
TO home_search_property_runtime;

GRANT USAGE, SELECT
ON SEQUENCE public.market_news_article_article_id_seq,
            public.market_news_relation_relation_id_seq
TO home_search_property_runtime;
