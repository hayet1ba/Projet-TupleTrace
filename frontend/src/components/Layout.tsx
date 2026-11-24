import { useEffect, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NAV, hasAny, roleText } from '../auth/roles';
import { USE_MOCKS, USE_MOCK_AUTH } from '../mocks/data';

function useOnline() {
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true), off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off); };
  }, []);
  return online;
}

export default function Layout() {
  const { user, logout } = useAuth();
  const online = useOnline();
  if (!user) return null;
  const items = NAV.filter((n) => hasAny(user, n.roles));

  return (
    <div className="shell">
      <a className="skip" href="#main">Aller au contenu</a>
      <aside className="side">
        <div className="brand">TupleTrace</div>
        <nav aria-label="Navigation principale">
          {items.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'}>{n.label}</NavLink>
          ))}
        </nav>
        <div>
          <div className="who">
            <span className="avatar" aria-hidden="true">{(user.displayName[0] ?? '?').toUpperCase()}</span>
            <div><strong>{user.displayName}</strong><span>{roleText(user)}</span></div>
          </div>
          <button className="ghost" onClick={() => void logout()}>Se déconnecter</button>
        </div>
      </aside>
      <div className="content">
        {USE_MOCKS && (
          <div className="banner warn" role="status">
            {USE_MOCK_AUTH ? 'Données simulées' : 'Authentification réelle, données métier simulées'} : aucune preuve, signature ou résultat métier affiché ici n'est réel.
          </div>
        )}
        {!online && (
          <div className="banner info" role="status">
            Vous êtes hors ligne. Seule l'interface est disponible ; aucune donnée métier n'est conservée.
          </div>
        )}
        <main id="main" tabIndex={-1}><Outlet /></main>
      </div>
    </div>
  );
}
