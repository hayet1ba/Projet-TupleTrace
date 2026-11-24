import { useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';

/** Ré-authentification (mot de passe + TOTP) quand la dernière preuve date de plus de 5 min. */
export default function StepUpForm({ onDone }: { onDone: () => void }) {
  const { reauth } = useAuth();
  const [password, setPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await reauth(password, otp); onDone(); }
    catch (err) { setError(err instanceof ApiError && err.kind === 'unauthenticated' ? 'Mot de passe ou code incorrect.' : errorMessage(err)); }
    finally { setBusy(false); }
  }
  return (
    <form className="card" onSubmit={(e) => void submit(e)} aria-labelledby="su">
      <h2 id="su">Confirmez votre identité</h2>
      <p>Cette action est sensible : votre dernière vérification date de plus de 5 minutes.</p>
      <label>Mot de passe
        <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <label>Code TOTP (6 chiffres)
        <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={otp} onChange={(e) => setOtp(e.target.value)} required />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" disabled={busy}>{busy ? 'Vérification…' : 'Confirmer'}</button>
    </form>
  );
}
