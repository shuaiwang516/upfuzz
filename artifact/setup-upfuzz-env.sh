#!/bin/bash

# sudo fdisk -l
sudo umount /mydata
sudo lvremove -y /dev/emulab/node*
sudo mkfs.ext4 -F /dev/sda4

# SSD
sudo mkdir /mnt_ssd
sudo mount /dev/sda4 /mnt_ssd
sudo chown $USER /mnt_ssd

# HDD
sudo mkfs.ext4 -F /dev/sdb
sudo mount /dev/sdb /mydata
sudo chown $USER /mydata 

mkdir /mydata/project
ln -s /mydata/project ~/project
sudo chown $USER ~/project/

sudo apt-get update
sudo apt-get install openjdk-11-jdk openjdk-8-jdk python2 maven fzf ant htop tmux -y -f

# trace figure
sudo apt install -y python3-pip
python3 -m pip install --user numpy matplotlib scipy

# Set up zsh
echo "Y" | sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"
sed -i 's/ZSH_THEME="robbyrussell"/ZSH_THEME="daveverwer"/' ~/.zshrc
echo "exec zsh" >> ~/.bashrc

# docker
sudo apt-get update
sudo apt-get install \
    ca-certificates \
    curl \
    gnupg \
    lsb-release -y
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-compose-plugin -y

sudo usermod -aG docker $USER
sudo systemctl stop docker.service
sudo systemctl stop docker.socket

sudo mv /var/lib/docker /mnt_ssd/
sudo ln -s /mnt_ssd/docker /var/lib/docker

sudo systemctl daemon-reload   
sudo systemctl start docker

sudo mkdir /mydata/test_binary
sudo chown $USER /mydata/test_binary

newgrp docker