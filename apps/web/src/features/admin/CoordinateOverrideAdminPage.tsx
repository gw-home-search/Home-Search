import { useEffect, useState, type FormEvent } from 'react';

import {
  approveCoordinateOverride,
  fetchCoordinatePendingComplexes,
  type CoordinatePendingComplex,
} from './api/fetchCoordinatePendingComplexes';

type AdminRequestState = 'loading' | 'ready' | 'empty' | 'error';

export function CoordinateOverrideAdminPage() {
  const [pending, setPending] = useState<CoordinatePendingComplex[]>([]);
  const [state, setState] = useState<AdminRequestState>('loading');
  const [error, setError] = useState<string | null>(null);
  const [selectedPnu, setSelectedPnu] = useState('');
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);
  const [reloadSeq, setReloadSeq] = useState(0);

  useEffect(() => {
    let ignore = false;
    setState('loading');
    setError(null);

    fetchCoordinatePendingComplexes()
      .then((items) => {
        if (ignore) {
          return;
        }
        setPending(items);
        setState(items.length === 0 ? 'empty' : 'ready');
      })
      .catch((nextError: unknown) => {
        if (ignore) {
          return;
        }
        setPending([]);
        setState('error');
        setError(nextError instanceof Error ? nextError.message : 'Unknown admin coordinate error');
      });

    return () => {
      ignore = true;
    };
  }, [reloadSeq]);

  async function handleApprove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const pnu = stringFormValue(formData, 'pnu').trim();
    const latitude = numberFormValue(formData, 'latitude');
    const longitude = numberFormValue(formData, 'longitude');
    const reason = stringFormValue(formData, 'reason').trim();
    const approvedBy = stringFormValue(formData, 'approvedBy').trim();

    setSubmitMessage(null);
    setError(null);
    try {
      const result = await approveCoordinateOverride(pnu, {
        latitude,
        longitude,
        reason,
        approvedBy,
      });
      setSubmitMessage(result.parcelUpdated ? 'Coordinate approved' : 'Override saved without parcel update');
      setSelectedPnu('');
      setReloadSeq((current) => current + 1);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unknown admin coordinate error');
    }
  }

  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <h1>Coordinate Overrides</h1>
          <p>Pending parcels with stored trades and missing marker coordinates.</p>
        </div>
        <a href="/" aria-label="Back to map">
          Map
        </a>
      </header>

      <section className="admin-workspace" aria-label="Coordinate override workspace">
        <div className="admin-list-panel">
          <div className="panel-section-header">
            <p>Pending</p>
            <span>{pending.length.toLocaleString()}</span>
          </div>

          {state === 'loading' ? <p role="status">Loading pending coordinates</p> : null}
          {state === 'empty' ? <p role="status">No coordinate-pending complexes</p> : null}
          {state === 'error' && error ? <p role="alert">{error}</p> : null}

          {pending.length > 0 ? (
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Complex</th>
                  <th scope="col">PNU</th>
                  <th scope="col">Trades</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {pending.map((item) => (
                  <tr key={`${item.parcelId}-${item.complexId}`}>
                    <td>
                      <strong>{item.aptName}</strong>
                      <span>{item.address ?? item.aptSeq ?? 'No address'}</span>
                    </td>
                    <td>{item.pnu}</td>
                    <td>{item.tradeCount.toLocaleString()}</td>
                    <td>
                      <button
                        type="button"
                        aria-label={`Select coordinate override ${item.pnu}`}
                        onClick={() => {
                          setSelectedPnu(item.pnu);
                        }}
                      >
                        Select
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>

        <form className="admin-override-form" aria-label="Approve coordinate override" onSubmit={handleApprove}>
          <div className="panel-section-header">
            <p>Manual Coordinate</p>
            <span>Approved</span>
          </div>
          <label>
            <span>PNU</span>
            <input name="pnu" required pattern="\\d{19}" value={selectedPnu} onChange={(event) => {
              setSelectedPnu(event.currentTarget.value);
            }} />
          </label>
          <label>
            <span>Latitude</span>
            <input name="latitude" required min="33" max="39" step="0.0000001" type="number" />
          </label>
          <label>
            <span>Longitude</span>
            <input name="longitude" required min="124" max="132" step="0.0000001" type="number" />
          </label>
          <label>
            <span>Reason</span>
            <textarea name="reason" rows={4} />
          </label>
          <label>
            <span>Approved by</span>
            <input name="approvedBy" required defaultValue="local-operator" />
          </label>
          <button type="submit" aria-label="Approve coordinate override">
            Approve
          </button>
          {submitMessage ? <p role="status">{submitMessage}</p> : null}
          {state !== 'error' && error ? <p role="alert">{error}</p> : null}
        </form>
      </section>
    </main>
  );
}

function stringFormValue(formData: FormData, field: string): string {
  const value = formData.get(field);
  return typeof value === 'string' ? value : '';
}

function numberFormValue(formData: FormData, field: string): number {
  const parsed = Number(stringFormValue(formData, field));
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}
