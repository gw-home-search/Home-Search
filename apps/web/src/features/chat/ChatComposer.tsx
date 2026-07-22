import {
  forwardRef,
  type FormEvent,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
} from 'react';

type ChatComposerProps = {
  disabled: boolean;
  isSending: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
  value: string;
};

const QUESTION_MIN_HEIGHT_PX = 24;
const QUESTION_MAX_HEIGHT_PX = 96;

export const ChatComposer = forwardRef<HTMLTextAreaElement, ChatComposerProps>(function ChatComposer({
  disabled,
  isSending,
  onChange,
  onSubmit,
  value,
}, forwardedRef) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  useImperativeHandle(forwardedRef, () => textareaRef.current as HTMLTextAreaElement, []);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (textarea == null) return;
    textarea.style.height = 'auto';
    const contentHeight = Math.max(textarea.scrollHeight, QUESTION_MIN_HEIGHT_PX);
    textarea.style.height = `${Math.min(contentHeight, QUESTION_MAX_HEIGHT_PX)}px`;
    textarea.style.overflowY = contentHeight > QUESTION_MAX_HEIGHT_PX ? 'auto' : 'hidden';
  }, [value]);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit();
  }

  return (
    <form className="chatbot-form" onSubmit={submit}>
      <div className="chatbot-form-heading">
        <label htmlFor="chatbot-question">홈서치 AI에게 질문하기</label>
      </div>
      <div className="chatbot-composer">
        <textarea
          disabled={disabled}
          id="chatbot-question"
          maxLength={2_000}
          name="chatbot-question"
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
            event.preventDefault();
            if (!disabled && value.trim().length > 0) event.currentTarget.form?.requestSubmit();
          }}
          placeholder="원하는 지역과 조건을 입력해 보세요."
          ref={textareaRef}
          rows={1}
          value={value}
        />
        <button
          aria-label={isSending ? '답변 생성 중' : '질문 보내기'}
          disabled={value.trim().length === 0 || disabled}
          type="submit"
        >
          {isSending ? <span aria-hidden="true" className="chatbot-sending-mark" /> : <SendIcon />}
        </button>
      </div>
      <p>답변은 신고 지연 등으로 실제와 다를 수 있으니 출처와 기준일을 확인해 주세요.</p>
    </form>
  );
});

function SendIcon() {
  return <svg aria-hidden="true" className="chatbot-send-icon" fill="none" viewBox="0 0 24 24"><path d="M12 18V6M6.5 11.5 12 6l5.5 5.5" /></svg>;
}
