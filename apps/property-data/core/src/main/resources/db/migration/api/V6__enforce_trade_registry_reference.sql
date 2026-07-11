ALTER TABLE public.trade_source_key_registry
    VALIDATE CONSTRAINT ck_trade_source_key_registry_trade_pair;

ALTER TABLE public.trade_source_key_registry
    ADD CONSTRAINT fk_trade_source_key_registry_trade
    FOREIGN KEY (trade_id, trade_deal_date)
    REFERENCES public.trade (id, deal_date)
    ON DELETE RESTRICT
    NOT VALID;

ALTER TABLE public.trade_source_key_registry
    VALIDATE CONSTRAINT fk_trade_source_key_registry_trade;
