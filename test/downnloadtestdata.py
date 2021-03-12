import os
import sys
import requests
from bs4 import BeautifulSoup

BASE_URL = "http://visa.lab.asu.edu/cifar-10/"
FOLDER_BASE = 'CIFAR'

testInputSize = sys.argv[1]
dataSetSize = {'1':100,'2':200,'3':300,'4':400,'5':500,'6':1000,'7':2000,'8':3000,'9':5000,'10':10000}
dataSetCount = dataSetSize[testInputSize]

dataSetFolderName = FOLDER_BASE + "_" +str(dataSetCount) + "/"


if not os.path.exists(dataSetFolderName):
    os.makedirs(dataSetFolderName)

resp = requests.get(BASE_URL)
soup = BeautifulSoup(resp.content,"html.parser")
links = soup.find_all('a')
counter = 0


for link in links: # Processing each link and getting the url value
    src = link.get('href')
    if ".png" in src:
        url = BASE_URL + src
        r = requests.get(url, allow_redirects=True)
        imagePath = dataSetFolderName + src
        with open(imagePath, 'wb') as file:
            file.write(r.content)
        if counter == dataSetCount:
            break
        counter += 1
