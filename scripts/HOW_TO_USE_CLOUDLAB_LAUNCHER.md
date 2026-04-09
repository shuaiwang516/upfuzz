  # Full deploy from scratch (setup + build + Docker + launch, 12h)
  python3 scripts/cloudlab_launcher.py deploy --timeout-sec 43200

  # Monitor (one-shot)
  python3 scripts/cloudlab_launcher.py monitor

  # Monitor (continuous, hourly)
  python3 scripts/cloudlab_launcher.py monitor --continuous --interval 3600

  # Stop everything
  python3 scripts/cloudlab_launcher.py stop

  # Download results
  python3 scripts/cloudlab_launcher.py download --dest /mnt/ssd/rupfuzz/cloudlab-results/apr10

  # Only mode 5 machines
  python3 scripts/cloudlab_launcher.py monitor --machines 1-6

  # Only mode 6 machines
  python3 scripts/cloudlab_launcher.py monitor --machines 7-12
