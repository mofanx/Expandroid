using System;
using System.Threading;
using System.Threading.Tasks;

#if ANDROID
using Android.Content;
using Android.Net;
#endif

namespace Expandroid.Services
{
    public class WebDavPollingService : IDisposable
    {
        private readonly SyncManager _syncManager;
        private Timer _pollTimer;
        private readonly int _wifiIntervalSec;
        private const int CellularIntervalSec = 300;
        private int _currentIntervalSec;
        private bool _disposed;

        public event Action OnRemoteChanged;

        public WebDavPollingService(SyncManager syncManager, int pollIntervalSec = 30)
        {
            _syncManager = syncManager;
            _wifiIntervalSec = pollIntervalSec;
            _currentIntervalSec = pollIntervalSec;
        }

        public void Start()
        {
            if (_pollTimer != null) return;
            _currentIntervalSec = GetCurrentIntervalSec();
            _pollTimer = new Timer(PollCallback, null,
                TimeSpan.FromSeconds(_currentIntervalSec),
                TimeSpan.FromSeconds(_currentIntervalSec));
        }

        public void Stop()
        {
            _pollTimer?.Dispose();
            _pollTimer = null;
        }

        public void UpdateInterval(int newIntervalSec)
        {
            if (_pollTimer == null) return;
            _pollTimer.Change(TimeSpan.FromSeconds(newIntervalSec), TimeSpan.FromSeconds(newIntervalSec));
        }

        private void PollCallback(object state)
        {
            if (_disposed) return;
            var newInterval = GetCurrentIntervalSec();
            if (newInterval != _currentIntervalSec)
            {
                _currentIntervalSec = newInterval;
                _pollTimer?.Change(TimeSpan.FromSeconds(_currentIntervalSec), TimeSpan.FromSeconds(_currentIntervalSec));
            }
            _ = TriggerPollAsync();
        }

        private async Task TriggerPollAsync()
        {
            try
            {
                if (_syncManager == null || !_syncManager.IsWebDav()) return;
                var hasChanges = _syncManager.CheckChanges();
                if (hasChanges)
                {
                    System.Diagnostics.Debug.WriteLine("WebDavPollingService: detected remote changes");
                    OnRemoteChanged?.Invoke();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WebDavPollingService poll failed: {ex.Message}");
            }
        }

        public void Dispose()
        {
            if (!_disposed)
            {
                _disposed = true;
                Stop();
            }
        }

        private int GetCurrentIntervalSec()
        {
#if ANDROID
            try
            {
                var context = Android.App.Application.Context;
                var cm = (ConnectivityManager)context.GetSystemService(Context.ConnectivityService);
                var activeNetwork = cm.ActiveNetwork;
                if (activeNetwork != null)
                {
                    var caps = cm.GetNetworkCapabilities(activeNetwork);
                    if (caps != null && caps.HasTransport(TransportType.Wifi))
                        return _wifiIntervalSec;
                }
                return CellularIntervalSec;
            }
            catch { return _wifiIntervalSec; }
#else
            return _wifiIntervalSec;
#endif
        }
    }
}
