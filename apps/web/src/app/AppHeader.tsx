import { Link } from 'react-router-dom';

import { AccountControl } from '../features/auth/AccountControl';
import { ChatbotPanel } from '../features/chat/ChatbotPanel';
import type { IndexedDbChatConversationStore } from '../features/chat/storage/chatConversationStore';
import type { ChatAction } from '../features/chat/actionContract';
import type { ChatUiContext } from '../features/chat/conversationContract';
import { FeatureErrorBoundary } from '../shared/FeatureErrorBoundary';

type AppHeaderProps = {
  chatConversationStore?: IndexedDbChatConversationStore;
  onChatOpenChange?: (isOpen: boolean) => void;
  onUiAction?: (action: ChatAction, source?: 'auto') => boolean;
  chatUiContext?: ChatUiContext;
};

export function AppHeader({ chatConversationStore, chatUiContext, onChatOpenChange, onUiAction }: AppHeaderProps) {
  return (
    <header aria-label="상단 앱 바" className="app-bar">
      <Link aria-label="홈서치 지도 홈" className="app-brand" to="/">
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
      </Link>
      <div className="app-header-actions">
        <FeatureErrorBoundary feature="chatbot">
          <ChatbotPanel
            onOpenChange={onChatOpenChange}
            onUiAction={onUiAction}
            store={chatConversationStore}
            uiContext={chatUiContext}
          />
        </FeatureErrorBoundary>
        <FeatureErrorBoundary feature="account">
          <AccountControl />
        </FeatureErrorBoundary>
      </div>
    </header>
  );
}
