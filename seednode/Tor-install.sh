!/bin/bash

# check for root
if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root" 1>&2
    exit 1
fi

PWD="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# update software
echo "== Updating software"
apt-get update
apt-get dist-upgrade -y

# install required software
apt-get install -y lsb-release gpg wget

# add official Tor repository
if ! grep -q "https://deb.torproject.org/torproject.org" /etc/apt/sources.list.d/tor.list; then
    echo "== Adding the official Tor repository"
    touch /etc/apt/sources.list.d/tor.list
    echo "deb [signed-by=/usr/share/keyrings/tor-archive-keyring.gpg] https://deb.torproject.org/torproject.org `lsb_release -cs` main" >> /etc/apt/sources.list.d/tor.list
    wget -qO- https://deb.torproject.org/torproject.org/A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89.asc | gpg --dearmor | tee /usr/share/keyrings/tor-archive-keyring.gpg >/dev/null
    apt-get update
fi

# install tor and related packages
echo "== Installing Tor and related packages"
apt-get install -y deb.torproject.org-keyring tor tor-geoipdb
service tor stop

echo "== Wait briefly for Tor to shut down cleanly"
sleep 30

# configure tor
echo "== Copy Tor configuration for Haveno seednode"
mv /etc/tor/torrc /etc/tor/torrc.bak
cp torrc /etc/tor/torrc

# copy `haveno-seednode.service` to `/etc/systemd/system`
#cp haveno-seednode.service /etc/systemd/system/haveno-seednode.service

service tor start

echo ""
echo "== Get your hiddenServiceAddress with:"
echo "cat /var/lib/tor/haveno-seednode/hostname"
echo "and insert them into: haveno-seednode.service"
echo "Modify User, Group & $PATH in /etc/systemd/system/haveno-seednode.service script"
echo ""
echo "When your private Monero node is running start Haveno seednode."
echo "systemctl start haveno-seednode"
echo ""
echo "Check if Haveno-Seednode is running properly."
echo "systemctl status haveno-seednode"
echo ""
echo "To have Haveno seednode start automatically at boot time, you need to enable it."
echo "systemctl enable haveno-seednode"
echo "systemctl daemon-reload"
echo ""
