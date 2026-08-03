export function FollowUpPrompts({
  onSelect,
  value,
}: {
  onSelect?: (question: string) => void;
  value: string;
}) {
  const questions = parseFollowUpQuestions(value);
  if (questions == null) return <p className="chatbot-summary-follow-up">{value}</p>;
  return (
    <section aria-label="이어서 물어보기" className="chatbot-follow-up-prompts">
      <h4>이어서 물어보기</h4>
      <div>{questions.map((question) => (
        <button key={question} onClick={() => onSelect?.(question)} type="button">
          {question}
        </button>
      ))}</div>
    </section>
  );
}

function parseFollowUpQuestions(value: string): string[] | null {
  if (!value.includes(' · ')) return null;
  const questions = value.split(' · ').map((item) => item.trim()).filter(Boolean);
  if (questions.length < 2 || questions.length > 3) return null;
  return questions;
}
