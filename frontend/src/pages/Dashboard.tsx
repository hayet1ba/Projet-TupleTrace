import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NAV, hasAny, roleText } from '../auth/roles';

export default function Dashboard() {
  const { user } = useAuth();
  if (!user) return null;
  const actions = NAV.filter((n) => n.to !== '/' && hasAny(user, n.roles));
  return (
    <section>
      <h1>Bonjour, {user.displayName}</h1>
      <p>Rôle actuel : <span className="badge b-PROTECTED">{roleText(user)}</span></p>
      <h2>Vos actions</h2>
      <ul className="actions">
        {actions.map((a) => <li key={a.to}><Link to={a.to}>{a.label}</Link></li>)}
      </ul>
    </section>
  );
}
