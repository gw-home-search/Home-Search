import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AdminApp } from './app/AdminApp';
import './styles/admin.css';
const root = document.getElementById('root');
if (root) createRoot(root).render(<StrictMode><AdminApp /></StrictMode>);
