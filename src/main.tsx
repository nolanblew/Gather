import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import AuthGate from './auth';
import './styles.css';
import { initializeInstall, registerServiceWorker } from './install';

initializeInstall();
if (import.meta.env.PROD) registerServiceWorker();

createRoot(document.getElementById('root')!).render(<React.StrictMode><AuthGate><App /></AuthGate></React.StrictMode>);
