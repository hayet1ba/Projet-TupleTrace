import { useState } from 'react';
import { ApiError, api, errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { roleText } from '../auth/roles';
import StepUpForm from '../components/StepUpForm';
import { USE_MOCK_AUTH } from '../mocks/data';

export default function Account() {
  const { user, isStrong, logout } = useAuth();
  const [stepUp, setStepUp] = useState(false);
  const [ok, setOk] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  if (!user) return null;

  async function revoke() {
    if (!isStrong()) return setStepUp(true);
    try {
      if (!USE_MOCK_AUTH) await api('/auth/factor/revoke', { method: 'POST' });
      await logout(); // le jeton lié au facteur n'est plus valide
    } catch (e) {
      if (e instanceof ApiError && e.kind === 'forbidden') setStepUp(true); // preuve trop ancienne
      else setMsg(errorMessage(e));
    }
  }

  return (
    <section>
      <h1>Mon compte</h1>
      <div className="card">
        <p><strong>{user.displayName}</strong></p>
        <p>Rôle(s) : {roleText(user)}</p>
        {user.expiresAt && <p>Session valable jusqu’à {new Date(user.expiresAt).toLocaleTimeString('fr-FR')} (15 minutes, sans renouvellement).</p>}
      </div>
      <div className="card">
        <h2>Second facteur (TOTP)</h2>
        <p>Le retirer vous déconnecte. Un compte propriétaire, administrateur ou auditeur doit ensuite être réenrôlé pour retrouver l’accès.</p>
        <label><span><input type="checkbox" checked={ok} onChange={(e) => setOk(e.target.checked)} /> Je comprends les conséquences</span></label>
        <button disabled={!ok} onClick={() => void revoke()}>Retirer mon second facteur</button>
        {msg && <p className="error" role="alert">{msg}</p>}
      </div>
      {stepUp && <StepUpForm onDone={() => { setStepUp(false); void revoke(); }} />}
    </section>
  );
}
