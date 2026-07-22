import { AccountControl } from '../features/auth/AccountControl';
import { ChatbotPanel } from '../features/chat/ChatbotPanel';
import type { IndexedDbChatConversationStore } from '../features/chat/storage/chatConversationStore';
import type { ChatAction } from '../features/chat/actionContract';

type AppHeaderProps = {
  chatConversationStore?: IndexedDbChatConversationStore;
  onChatOpenChange?: (isOpen: boolean) => void;
  onUiAction?: (action: ChatAction) => boolean;
};

export function AppHeader({ chatConversationStore, onChatOpenChange, onUiAction }: AppHeaderProps) {
  return (
    <header aria-label="상단 앱 바" className="app-bar">
      <div className="app-brand">
        <img
          alt=""
          aria-hidden="true"
          className="app-brand-mark"
          height="38"
          src="/home-search-logo.png"
          width="38"
        />
        <span className="app-brand-copy">
          <h1>홈서치</h1>
          <span>HomeSearch · 실거래가 인사이트</span>
        </span>
      </div>
      <div className="app-header-actions">
        <ChatbotPanel
          onOpenChange={onChatOpenChange}
          onUiAction={onUiAction}
          store={chatConversationStore}
        />
        <AccountControl />
      </div>
    </header>
  );
}
