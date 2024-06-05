pipeline {
    agent any
    
    environment {
        sshkey = credentials('ubuntu_a705')
        releaseServerAccount = 'ubuntu'
        releaseServerUri = 'i10a705.p.ssafy.io'
    }
    
    stages {
        stage('Git Clone') {
            steps {
                git branch: 'develop',
                    credentialsId: 'gitlab-a705',
                    url: 'https://lab.ssafy.com/s10-webmobile1-sub2/S10P12A705.git'
            }
        }
         stage('check frontChange') {
            steps {
                script {
                    def gitDiffResult = sh(script: "git diff --name-only HEAD^ HEAD", returnStdout: true).trim()
                    println("gitDiffResult: " + gitDiffResult)
                    
                    def egrepResult = sh(script: "echo '${gitDiffResult}' | egrep '(\\.js|\\.html|\\.vue|\\.css|\\.json)\$' || true", returnStdout: true).trim()
                    println("egrepResult: " + egrepResult)
     
                    def services = sh(script: "echo '${egrepResult}' | cut -d/ -f1 | uniq", returnStdout: true).trim().split("\n")
                    println("services: " + services)

                    println("serviceslength: " + services.length)
                        
                    def change = services[0]
                    println("change: " + change)

                    if (change == null || change == "server" || change == "" || change == " ") {
                        // services[0]에 값이 있을 때의 추가 작업 수행
                        echo "Change found: ${change}"
                        echo "No services to build. Exiting pipeline."
                        currentBuild.result = 'SUCCESS'
                        error("nothing to build. done")        
                    }
                }
            }
        }

        stage('Transfer front file to EC2') {
            steps {
                script {
                    sshagent(['ubuntu_a705']) {
                        sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'rm -rf front && ls -al'"
                        sh "scp -i ${sshkey} -r ./front $releaseServerAccount@$releaseServerUri:/home/ubuntu"
                        sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'ls -al'"
                    }
                }
            }
        }

        stage('Build and Install Dependencies at EC2 and Mofiy js file') {
            steps {
                // Add deployment steps for your specific scenario on EC2
                script {
                    sshagent(credentials: ['ubuntu_a705']) {
                        def result = sh(script: "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'cd ./front && npm install && npm run build'", returnStatus: true)
                        if (result != 0) {
                            sh 'curl -d \'{"text":":sad_pepe: FRONT 배포실패!. 혹시 올리기전 npm run build를 확인하셨나요? :sad_pepe:"}\' -H "Content-Type: application/json" -X POST https://meeting.ssafy.com/hooks/tj7coo5cgi8oddxzqexh669a6e'
                            error("fail to build. done")     
                        }
                        sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri './modifyFront.sh'"
                    }
                }

                
            }
        }

        stage('Deploy') {
            steps {
                // Add deployment steps for your specific scenario on EC2
                script {
                    sshagent(credentials: ['ubuntu_a705']) {  
                        // front를 내리고 백그라운드에서 실행
                        sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'docker stop front && docker rm front'"
                        sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'docker run -d --name front -p 8000:8000 -v /home/ubuntu/front/dist/spa:/usr/share/nginx/html -v /home/ubuntu/front_config:/etc/nginx/conf.d nginx:latest'"
                    }
                }
            }
        }
        
        stage('Service Check') {
            steps {
                sshagent(credentials: ['ubuntu_a705']) {
                    sh '''
                        #!/bin/bash
                        sleep 10
                        for retry_count in \$(seq 20)
                        do
                            if curl -s "http://i10a705.p.ssafy.io" > /dev/null
                            then
                                curl -d '{"text":":cat_clap: FRONT Release Complete :cat_clap:"}' -H "Content-Type: application/json" -X POST https://meeting.ssafy.com/hooks/tj7coo5cgi8oddxzqexh669a6e
                                break
                            fi
                        
                            if [ $retry_count -eq 20 ]
                            then
                            curl -d '{"text":":sad_pepe:  FRONT Release Fail :sad_pepe:"}' -H "Content-Type: application/json" -X POST https://meeting.ssafy.com/hooks/tj7coo5cgi8oddxzqexh669a6e
                            exit 1
                            fi
                        
                            echo "The server is not alive yet. Retry health check in 5 seconds..."
                            sleep 5
                        done
                    '''
                }
            }
        }
    }
}
